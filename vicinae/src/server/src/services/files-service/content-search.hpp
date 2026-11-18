#pragma once
#include <QByteArray>
#include <QObject>
#include <QProcess>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace content_search {

// Time each keyword level is allowed to spend, in total. Documents (text files and PDFs) go first,
// images (OCR) only get what is left, so the whole keyword phase never passes KEYWORDS_BUDGET.
inline constexpr std::chrono::milliseconds DOCUMENTS_BUDGET{6000};
inline constexpr std::chrono::milliseconds MEDIA_BUDGET{4000};
inline constexpr std::chrono::milliseconds KEYWORDS_BUDGET{DOCUMENTS_BUDGET + MEDIA_BUDGET};

enum class Scope : std::uint8_t {
  Documents, // plain text files and PDFs
  Media,     // images, matched through OCR
};

struct Match {
  std::filesystem::path path;
  std::string snippet;
};

} // namespace content_search

/**
 * Search for keywords inside file contents, one scope at a time.
 *
 * Text files are matched with ripgrep, PDFs with pdftotext and images with tesseract OCR. A tool
 * that is not installed only makes the search return fewer matches, it never fails the stage.
 * Every stage has its own deadline and is cancelled as soon as another search starts, so a slow
 * filesystem can never keep the launcher waiting. Matches are reported while the stage runs, and
 * the last callback (finished = true) marks the end of it.
 */
class ContentSearch : public QObject {
  Q_OBJECT

public:
  using Scope = content_search::Scope;
  using Match = content_search::Match;
  using Callback = std::function<void(std::vector<Match> matches, bool finished)>;

  explicit ContentSearch(QObject *parent = nullptr);

  void search(Scope scope, const std::string &query, std::chrono::milliseconds budget, Callback callback);
  void cancel();
  bool isRunning() const { return m_running; }

private:
  struct Job {
    std::filesystem::path path;
    QString program;
    QStringList arguments;
  };

  void startDocuments();
  void startMedia();
  void spawnTextSearch();
  void spawn(const QString &program, const QStringList &arguments,
             const std::function<void(const QByteArray &)> &onOutput);
  void queueFiles(const QByteArray &listing, int limit);
  void processQueue();
  void addTextLine(const QByteArray &line);
  void addMatch(const std::filesystem::path &path, const QString &snippet);
  void releaseProcess(QProcess *process);
  void finishIfIdle();
  void finish();
  void cleanupProcesses();

  std::string m_query;
  std::vector<QString> m_words;
  Callback m_callback;
  Scope m_scope = Scope::Documents;
  bool m_running = false;
  int m_pending = 0;
  int m_activeJobs = 0;
  int m_concurrency = 1;
  int m_maxResults = 0;
  std::vector<Match> m_matches;
  std::vector<Job> m_queue;
  std::vector<QProcess *> m_processes;
  QTimer m_deadline;
};
