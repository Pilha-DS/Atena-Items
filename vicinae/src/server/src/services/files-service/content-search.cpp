#include "services/files-service/content-search.hpp"
#include "utils/utils.hpp"
#include <algorithm>
#include <array>
#include <cstdlib>
#include <memory>
#include <optional>
#include <system_error>
#include <utility>

namespace {

using Scope = content_search::Scope;

constexpr std::chrono::milliseconds DOCUMENTS_DEADLINE{content_search::DOCUMENTS_BUDGET};
constexpr std::chrono::milliseconds MEDIA_DEADLINE{content_search::MEDIA_BUDGET};
constexpr int DOCUMENTS_MAX_RESULTS = 6;
constexpr int MEDIA_MAX_RESULTS = 4;
// Each conversion is a full subprocess, so the limits are kept small on purpose: the keyword
// levels are a last resort for when the file index found nothing, not a second full search.
constexpr int PDF_FILE_LIMIT = 8;
constexpr int IMAGE_FILE_LIMIT = 6;
constexpr int DOCUMENTS_CONCURRENCY = 3;
constexpr int MEDIA_CONCURRENCY = 2;
constexpr int SNIPPET_RADIUS = 60;
constexpr int MAX_SNIPPET_LENGTH = 160;
constexpr int MAX_TEXT_FILE_SIZE = 4 * 1024 * 1024;
constexpr int PDF_MAX_PAGES = 20;

constexpr auto EXCLUDED_GLOBS = std::to_array<const char *>({
    "!**/node_modules/**",
    "!**/.git/**",
    "!**/.cache/**",
    "!**/.gradle/**",
    "!**/__pycache__/**",
    "!**/.venv/**",
    "!**/venv/**",
    "!**/.local/share/Trash/**",
});

constexpr auto IMAGE_GLOBS = std::to_array<const char *>({
    "*.png",
    "*.jpg",
    "*.jpeg",
    "*.webp",
    "*.bmp",
    "*.tiff",
    "*.heic",
});

QString homePath() { return QString::fromStdString(homeDir().string()); }

/**
 * Tesseract only parses the languages whose traineddata is installed, and asking for a missing one
 * makes it fail the whole image. We therefore pick from a preferred list using what is on disk.
 */
QStringList ocrLanguageArguments() {
  QStringList dirs;
  if (const auto *prefix = std::getenv("TESSDATA_PREFIX"); prefix && *prefix != '\0') {
    dirs << QString::fromLocal8Bit(prefix);
  }
  dirs << QStringLiteral("/usr/share/tessdata") << QStringLiteral("/usr/share/tesseract-ocr/5/tessdata");

  auto available = [&](const QString &language) {
    return std::ranges::any_of(dirs, [&](const QString &dir) {
      std::error_code ec;
      auto file = std::filesystem::path{dir.toStdString()} / (language + ".traineddata").toStdString();
      return std::filesystem::exists(file, ec);
    });
  };

  QStringList languages;
  for (const auto *language : {"por", "eng"}) {
    if (available(QString::fromLatin1(language))) languages << QString::fromLatin1(language);
  }

  return languages.isEmpty() ? QStringList{} : QStringList{"-l", languages.join(u'+')};
}

QStringList globArguments() {
  QStringList args;
  for (const auto *glob : EXCLUDED_GLOBS) {
    args << "-g" << QString::fromLatin1(glob);
  }
  return args;
}

std::vector<QString> splitWords(const std::string &query) {
  std::vector<QString> words;
  auto split = QString::fromStdString(query).split(u' ', Qt::SkipEmptyParts);
  words.reserve(static_cast<size_t>(split.size()));
  for (const auto &word : split) {
    const auto lowered = word.toLower();
    if (!lowered.isEmpty()) words.emplace_back(lowered);
  }
  return words;
}

std::optional<QString> snippetAround(const QString &text, const std::vector<QString> &words) {
  if (words.empty() || text.isEmpty()) return std::nullopt;

  const auto lowered = text.toLower();
  qsizetype firstMatch = -1;

  for (const auto &word : words) {
    const auto index = lowered.indexOf(word);
    if (index < 0) return std::nullopt;
    if (firstMatch < 0 || index < firstMatch) firstMatch = index;
  }

  if (firstMatch < 0) return std::nullopt;

  const auto start = std::max<qsizetype>(0, firstMatch - SNIPPET_RADIUS);
  const auto length = std::min<qsizetype>(MAX_SNIPPET_LENGTH, text.size() - start);
  auto snippet = text.mid(start, std::max<qsizetype>(length, 0)).simplified();

  if (start > 0) snippet.prepend(QStringLiteral("…"));
  if (start + length < text.size()) snippet.append(QStringLiteral("…"));

  return snippet;
}

void sortByMostRecent(std::vector<std::filesystem::path> &paths) {
  std::ranges::sort(paths, [](const std::filesystem::path &a, const std::filesystem::path &b) {
    std::error_code ecA;
    std::error_code ecB;
    const auto timeA = std::filesystem::last_write_time(a, ecA);
    const auto timeB = std::filesystem::last_write_time(b, ecB);
    if (ecA || ecB) return !ecA;
    return timeA > timeB;
  });
}

} // namespace

ContentSearch::ContentSearch(QObject *parent) : QObject(parent) {
  m_deadline.setSingleShot(true);
  connect(&m_deadline, &QTimer::timeout, this, &ContentSearch::finish);
}

void ContentSearch::search(Scope scope, const std::string &query, std::chrono::milliseconds budget,
                           Callback callback) {
  cancel();

  m_scope = scope;
  m_query = query;
  m_words = splitWords(query);
  m_callback = std::move(callback);
  m_maxResults = scope == Scope::Documents ? DOCUMENTS_MAX_RESULTS : MEDIA_MAX_RESULTS;
  m_concurrency = scope == Scope::Documents ? DOCUMENTS_CONCURRENCY : MEDIA_CONCURRENCY;

  if (m_words.empty()) {
    auto empty = std::exchange(m_callback, nullptr);
    if (empty) empty({}, true);
    return;
  }

  auto const stageBudget = scope == Scope::Documents ? DOCUMENTS_DEADLINE : MEDIA_DEADLINE;
  if (budget <= std::chrono::milliseconds::zero()) budget = std::chrono::milliseconds::zero();
  auto const effectiveBudget = std::min(budget, stageBudget);

  if (effectiveBudget <= std::chrono::milliseconds::zero()) {
    auto empty = std::exchange(m_callback, nullptr);
    if (empty) empty({}, true);
    return;
  }

  m_running = true;
  m_deadline.start(effectiveBudget);

  if (scope == Scope::Documents) {
    startDocuments();
  } else {
    startMedia();
  }
}

void ContentSearch::cancel() {
  if (!m_running && m_processes.empty()) {
    m_callback = nullptr;
    return;
  }

  m_running = false;
  m_callback = nullptr;
  m_matches.clear();
  m_queue.clear();
  m_pending = 0;
  m_activeJobs = 0;
  m_deadline.stop();
  cleanupProcesses();
}

void ContentSearch::startDocuments() {
  spawnTextSearch();

  QStringList listing = {"--files", "--no-config", "--no-messages", "--hidden", "-g", "*.pdf"};
  listing << globArguments() << homePath();

  spawn("rg", listing, [this](const QByteArray &output) { queueFiles(output, PDF_FILE_LIMIT); });
}

void ContentSearch::startMedia() {
  QStringList listing = {"--files", "--no-config", "--no-messages", "--hidden"};
  for (const auto *glob : IMAGE_GLOBS) {
    listing << "-g" << QString::fromLatin1(glob);
  }
  listing << globArguments() << homePath();

  spawn("rg", listing, [this](const QByteArray &output) { queueFiles(output, IMAGE_FILE_LIMIT); });
}

void ContentSearch::spawnTextSearch() {
  QStringList arguments = {"--no-config",     "--no-messages",
                           "--hidden",        "--no-heading",
                           "--with-filename", "--line-number",
                           "--color=never",   "--max-count=1",
                           "--max-filesize",  QString::number(MAX_TEXT_FILE_SIZE),
                           "--smart-case",    "--fixed-strings",
                           "--threads",       "4"};
  arguments << globArguments() << "--" << QString::fromStdString(m_query) << homePath();

  auto *process = new QProcess(this);
  auto buffer = std::make_shared<QByteArray>();
  ++m_pending;
  m_processes.push_back(process);

  connect(process, &QProcess::readyReadStandardOutput, this, [this, process, buffer]() {
    buffer->append(process->readAllStandardOutput());

    qsizetype newline = 0;
    while ((newline = buffer->indexOf('\n')) >= 0) {
      const QByteArray line = buffer->left(newline);
      buffer->remove(0, newline + 1);
      addTextLine(line);

      if (std::cmp_greater_equal(m_matches.size(), static_cast<size_t>(m_maxResults))) {
        buffer->clear();
        process->kill();
        return;
      }
    }
  });

  connect(process, &QProcess::errorOccurred, this, [this, process](QProcess::ProcessError error) {
    if (error != QProcess::FailedToStart) return;
    releaseProcess(process);
    --m_pending;
    finishIfIdle();
  });

  connect(process, &QProcess::finished, this, [this, process]() {
    releaseProcess(process);
    --m_pending;
    finishIfIdle();
  });

  process->start(QStringLiteral("rg"), arguments);
}

void ContentSearch::spawn(const QString &program, const QStringList &arguments,
                          const std::function<void(const QByteArray &)> &onOutput) {
  auto *process = new QProcess(this);
  ++m_pending;
  m_processes.push_back(process);

  connect(process, &QProcess::errorOccurred, this, [this, process, onOutput](QProcess::ProcessError error) {
    if (error != QProcess::FailedToStart) return;
    onOutput({});
    releaseProcess(process);
    --m_pending;
    finishIfIdle();
  });

  connect(process, &QProcess::finished, this, [this, process, onOutput]() {
    if (!m_running) {
      releaseProcess(process);
      return;
    }
    auto output = process->readAllStandardOutput();
    onOutput(output);
    releaseProcess(process);
    --m_pending;
    finishIfIdle();
  });

  process->start(program, arguments);
}

void ContentSearch::queueFiles(const QByteArray &listing, int limit) {
  std::vector<std::filesystem::path> paths;
  paths.reserve(static_cast<size_t>(listing.count('\n')) + 1);

  for (const auto &line : listing.split('\n')) {
    if (line.isEmpty()) continue;
    std::filesystem::path path{QString::fromUtf8(line).toStdString()};
    std::error_code ec;
    if (std::filesystem::is_regular_file(path, ec)) paths.emplace_back(std::move(path));
  }

  sortByMostRecent(paths);

  if (paths.size() > static_cast<size_t>(limit)) paths.resize(static_cast<size_t>(limit));

  m_queue.reserve(m_queue.size() + paths.size());

  for (auto &path : paths) {
    Job job{.path = std::move(path)};
    const auto raw = QString::fromStdString(job.path.string());
    if (m_scope == Scope::Documents) {
      job.program = QStringLiteral("pdftotext");
      job.arguments = {QStringLiteral("-enc"),
                       QStringLiteral("UTF-8"),
                       QStringLiteral("-l"),
                       QString::number(PDF_MAX_PAGES),
                       raw,
                       QStringLiteral("-")};
    } else {
      job.program = QStringLiteral("tesseract");
      job.arguments = {QStringLiteral("--psm"), QStringLiteral("6")};
      job.arguments << ocrLanguageArguments() << raw << QStringLiteral("-");
    }
    m_queue.emplace_back(std::move(job));
  }

  processQueue();
}

void ContentSearch::processQueue() {
  while (m_running && m_activeJobs < m_concurrency && !m_queue.empty()) {
    Job job = std::move(m_queue.back());
    m_queue.pop_back();
    ++m_activeJobs;

    auto path = job.path;
    spawn(job.program, job.arguments, [this, path](const QByteArray &output) {
      --m_activeJobs;
      if (!m_running) return;

      const auto text = QString::fromUtf8(output);
      if (auto snippet = snippetAround(text, m_words)) { addMatch(path, *snippet); }
      processQueue();
    });
  }
}

void ContentSearch::addTextLine(const QByteArray &line) {
  if (line.isEmpty()) return;

  const auto text = QString::fromUtf8(line);
  qsizetype const first = text.indexOf(u':');
  if (first <= 0) return;

  qsizetype const second = text.indexOf(u':', first + 1);
  if (second < 0) return;

  const auto content = text.mid(second + 1).trimmed();
  if (content.isEmpty()) return;

  addMatch(std::filesystem::path{text.left(first).toStdString()}, content.left(MAX_SNIPPET_LENGTH));
}

void ContentSearch::addMatch(const std::filesystem::path &path, const QString &snippet) {
  if (std::cmp_greater_equal(m_matches.size(), static_cast<size_t>(m_maxResults))) return;

  if (std::ranges::any_of(m_matches, [&](const Match &match) { return match.path == path; })) return;

  m_matches.push_back({.path = path, .snippet = snippet.toStdString()});

  // Hand the matches over as they show up: a keyword search only finishes when its deadline is
  // reached, and waiting for that to display anything would make the launcher look stuck.
  if (m_callback) m_callback(m_matches, false);
}

void ContentSearch::releaseProcess(QProcess *process) {
  m_processes.erase(std::remove(m_processes.begin(), m_processes.end(), process), m_processes.end());
  process->deleteLater();
}

void ContentSearch::finishIfIdle() {
  if (m_running && m_pending <= 0) finish();
}

void ContentSearch::finish() {
  if (!m_running) return;

  m_running = false;
  m_deadline.stop();
  m_pending = 0;
  m_activeJobs = 0;
  m_queue.clear();
  cleanupProcesses();

  auto callback = std::exchange(m_callback, nullptr);
  auto matches = std::move(m_matches);
  m_matches.clear();

  if (callback) callback(std::move(matches), true);
}

void ContentSearch::cleanupProcesses() {
  for (auto *process : m_processes) {
    QObject::disconnect(process, nullptr, this, nullptr);
    process->kill();
    process->deleteLater();
  }
  m_processes.clear();
}
