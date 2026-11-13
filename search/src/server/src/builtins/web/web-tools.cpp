#include "web-tools.hpp"
#include <algorithm>
#include <array>
#include <deque>
#include <format>
#include <functional>
#include <memory>
#include <utility>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFutureWatcher>
#include <QHash>
#include <QHostAddress>
#include <QPromise>
#include <QRegularExpression>
#include <QTimer>
#include <QUrl>
#include <QUrlQuery>
#include <glaze/json/write.hpp>
#include "internal/http-client.hpp"

namespace web_tools {

constexpr auto SEARCH_ENDPOINT = "https://lite.duckduckgo.com/lite/";
constexpr int DEFAULT_LIMIT = 8;
constexpr int MAX_LIMIT = 20;
constexpr std::size_t MAX_OUTPUT_BYTES = 64 * 1024;

// DuckDuckGo challenges requests that do not look like they come from a browser, so the search talks
// like one. Pages fetched on behalf of the user get an honest user agent instead: passing as a browser
// gets a 403 from sites that serve bots differently.
constexpr auto SEARCH_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
constexpr auto FETCH_AGENT = "Vicinae (+https://vicinae.com)";

struct SearchInput {
  std::string query;
  std::optional<int> limit;
};

struct SearchResult {
  std::string title;
  std::string url;
  std::string snippet;
};

struct FetchInput {
  std::string url;
};

struct FetchResult {
  std::string url;
  std::string content;
  bool truncated = false;
};

AI::AbstractTool::RawToolTask ready(AI::AbstractTool::RawToolResult result) {
  return {QtFuture::makeReadyValueFuture(std::move(result)), {}};
}

QString decodeEntities(const QString &text) {
  static const QHash<QString, QString> named = {
      {QStringLiteral("amp"), QStringLiteral("&")},  {QStringLiteral("lt"), QStringLiteral("<")},
      {QStringLiteral("gt"), QStringLiteral(">")},   {QStringLiteral("quot"), QStringLiteral("\"")},
      {QStringLiteral("apos"), QStringLiteral("'")}, {QStringLiteral("nbsp"), QStringLiteral(" ")},
      {QStringLiteral("ndash"), QStringLiteral("-")}, {QStringLiteral("mdash"), QStringLiteral("-")},
      {QStringLiteral("hellip"), QStringLiteral("...")}, {QStringLiteral("rsquo"), QStringLiteral("'")},
      {QStringLiteral("lsquo"), QStringLiteral("'")}, {QStringLiteral("ldquo"), QStringLiteral("\"")},
      {QStringLiteral("rdquo"), QStringLiteral("\"")},
  };
  static const QRegularExpression entity(QStringLiteral("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);"));

  QString out;
  out.reserve(text.size());
  qsizetype last = 0;

  auto matches = entity.globalMatch(text);
  while (matches.hasNext()) {
    const auto match = matches.next();
    out += QStringView{text}.mid(last, match.capturedStart() - last);
    const auto name = match.captured(1);

    if (name.startsWith(u'#')) {
      bool ok = false;
      const auto code = name.startsWith(QLatin1String("#x"), Qt::CaseInsensitive)
                            ? name.mid(2).toUInt(&ok, 16)
                            : name.mid(1).toUInt(&ok, 10);
      if (ok && code > 0 && code <= 0x10FFFF) {
        const char32_t point = code;
        out += QString::fromUcs4(&point, 1);
      } else {
        out += match.captured(0);
      }
    } else if (const auto value = named.constFind(name); value != named.constEnd()) {
      out += *value;
    } else {
      out += match.captured(0);
    }

    last = match.capturedEnd();
  }

  out += QStringView{text}.mid(last);
  return out;
}

QString stripTags(const QString &html) {
  static const QRegularExpression tags(QStringLiteral("<[^>]*>"));
  auto text = html;
  text.remove(tags);
  text = decodeEntities(text);
  text.replace(QRegularExpression(QStringLiteral("[\\s\\x{00a0}]+")), QStringLiteral(" "));
  return text.trimmed();
}

/// Readable text of a page: metadata, scripts and styling dropped, block elements turned into line breaks.
QString htmlToText(QString html) {
  static const QRegularExpression ignored(
      QStringLiteral("<(script|style|noscript|template|svg|title)\\b[^>]*>.*?</\\1>"),
      QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);
  static const QRegularExpression head(QStringLiteral("<head\\b[^>]*>.*?</head>"),
                                       QRegularExpression::CaseInsensitiveOption |
                                           QRegularExpression::DotMatchesEverythingOption);
  static const QRegularExpression comments(QStringLiteral("<!--.*?-->"),
                                           QRegularExpression::DotMatchesEverythingOption);
  // Both ends matter: a paragraph's opening tag already separates it from what came before.
  static const QRegularExpression blocks(
      QStringLiteral("<\\s*/?\\s*(p|div|li|tr|ul|ol|dl|dt|dd|h[1-6]|section|article|blockquote|pre|table|"
                     "header|footer|nav|main|aside|figure|figcaption|form|br|hr)\\b[^>]*>"),
      QRegularExpression::CaseInsensitiveOption);
  static const QRegularExpression tags(QStringLiteral("<[^>]*>"));
  static const QRegularExpression spaces(QStringLiteral("[ \\t\\x{00a0}]+"));
  static const QRegularExpression trailing(QStringLiteral("[ \\t]+\\n"));
  static const QRegularExpression breaks(QStringLiteral("\\n{3,}"));

  html.remove(ignored);
  html.remove(head);
  html.remove(comments);
  html.replace(QStringLiteral("\r\n"), QStringLiteral("\n"));
  html.replace(blocks, QStringLiteral("\n"));
  html.remove(tags);
  html = decodeEntities(html);
  html.replace(spaces, QStringLiteral(" "));
  html.replace(trailing, QStringLiteral("\n"));
  html.replace(breaks, QStringLiteral("\n\n"));
  return html.trimmed();
}

bool looksLikeHtml(std::string_view body) {
  const auto head = QByteArrayView(body).left(1024).toByteArray().toLower();
  return head.contains("<!doctype html") || head.contains("<html") || head.contains("<body") ||
         head.contains("<div") || head.contains("<p>") || head.contains("<meta");
}

/// Whether the host points back at the machine or at a network a page has no business reaching.
bool isBlockedHost(const QString &host) {
  const auto lowered = host.toLower();

  if (lowered == QLatin1String("localhost") || lowered.endsWith(QLatin1String(".localhost")) ||
      lowered.endsWith(QLatin1String(".local")) || lowered.endsWith(QLatin1String(".internal"))) {
    return true;
  }

  QHostAddress address;
  // A name is resolved by the network layer; only literals can be checked here.
  if (!address.setAddress(lowered)) return false;

  if (address.isNull() || address.isLoopback() || address.isLinkLocal()) return true;

  // Ranges a page has no reason to reach: they belong to the machine or to its local network.
  static const std::array<std::pair<const char *, int>, 6> blocked4 = {{
      {"10.0.0.0", 8},
      {"172.16.0.0", 12},
      {"192.168.0.0", 16},
      {"127.0.0.0", 8},
      {"100.64.0.0", 10},
      {"0.0.0.0", 8},
  }};

  for (const auto &[network, prefix] : blocked4) {
    if (address.isInSubnet(QHostAddress(QString::fromUtf8(network)), prefix)) return true;
  }

  // fc00::/7, unique local addresses
  return address.isInSubnet(QHostAddress(QStringLiteral("fc00::")), 7);
}

/// Search engines hand out redirect links that carry the real destination in `uddg`.
QString unwrapSearchUrl(QString url) {
  url = decodeEntities(url);
  if (url.startsWith(QLatin1String("//"))) url.prepend(QLatin1String("https:"));

  const auto query = QUrlQuery(QUrl(url).query());
  if (query.hasQueryItem(QLatin1String("uddg"))) return query.queryItemValue(QLatin1String("uddg"));
  return url;
}

std::vector<SearchResult> parseDuckDuckGo(const QString &page) {
  static const QRegularExpression anchor(QStringLiteral("<a\\b([^>]*)>(.*?)</a>"),
                                         QRegularExpression::DotMatchesEverythingOption |
                                             QRegularExpression::CaseInsensitiveOption);
  static const QRegularExpression href(QStringLiteral("href\\s*=\\s*[\"']([^\"']*)[\"']"),
                                       QRegularExpression::CaseInsensitiveOption);
  static const QRegularExpression snippet(
      QStringLiteral("<td[^>]*class\\s*=\\s*[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>(.*?)</td>"),
      QRegularExpression::DotMatchesEverythingOption | QRegularExpression::CaseInsensitiveOption);

  struct Hit {
    qsizetype start;
    qsizetype end;
    QString title;
    QString url;
  };

  std::vector<Hit> hits;
  auto anchors = anchor.globalMatch(page);
  while (anchors.hasNext()) {
    const auto match = anchors.next();
    if (!match.captured(1).contains(QLatin1String("result-link"))) continue;

    const auto link = href.match(match.captured(1));
    if (!link.hasMatch()) continue;

    hits.push_back({.start = match.capturedStart(),
                    .end = match.capturedEnd(),
                    .title = stripTags(match.captured(2)),
                    .url = unwrapSearchUrl(link.captured(1))});
  }

  std::vector<SearchResult> results;
  results.reserve(hits.size());

  for (std::size_t i = 0; i < hits.size(); ++i) {
    const qsizetype next = i + 1 < hits.size() ? hits[i + 1].start : page.size();
    const auto segment = QStringView{page}.mid(hits[i].end, next - hits[i].end);
    const auto found = snippet.match(segment.toString());

    results.push_back({.title = hits[i].title.toStdString(),
                       .url = hits[i].url.toStdString(),
                       .snippet = found.hasMatch() ? stripTags(found.captured(1)).toStdString()
                                                   : std::string()});
  }

  return results;
}

/**
 * DuckDuckGo answers a burst of queries with a challenge page, so searches go through a single queue:
 * one request in flight at a time, starting no more often than `SEARCH_INTERVAL_MS`. Bursts are still
 * answered, in order, just spread out.
 */
class SearchThrottle : public QObject {
public:
  static constexpr qint64 SEARCH_INTERVAL_MS = 1000; // at most two searches per two seconds

  using Result = AI::AbstractTool::RawToolResult;
  using Request = std::function<QFuture<Result>()>;

  static SearchThrottle &instance() {
    static SearchThrottle throttle;
    return throttle;
  }

  QFuture<Result> submit(Request request, std::function<void()> &cancel) {
    cancel = [] {};

    // queuing during a cooldown would just make the model wait for a request that cannot succeed
    if (const qint64 now = QDateTime::currentMSecsSinceEpoch(); now < m_cooldownUntil) {
      const auto seconds = (m_cooldownUntil - now + 999) / 1000;
      return QtFuture::makeReadyValueFuture<Result>(std::unexpected(std::format(
          "Web search is paused for about {}s: DuckDuckGo refused the last request as automated traffic. "
          "Do not retry in a loop; tell the user, or read a page with web_fetch if you know its URL.",
          seconds)));
    }

    auto task = std::make_shared<Task>(Task{.request = std::move(request)});
    auto future = task->promise.future();
    m_queue.push_back(task);

    cancel = [this, task] {
      if (task->finished) return;
      if (task->started) return; // the request is already out, let it finish

      if (const auto it = std::ranges::find(m_queue, task); it != m_queue.end()) m_queue.erase(it);
      task->finished = true;
      task->promise.addResult(std::unexpected(std::string("The search was cancelled.")));
      task->promise.finish();
    };

    pump();
    return future;
  }

  /**
   * Stop handing out requests after the engine pushed back: retrying right away only deepens the block.
   */
  void cooldownFor(std::chrono::seconds duration) {
    const qint64 until = QDateTime::currentMSecsSinceEpoch() + duration.count() * 1000;
    m_cooldownUntil = std::max(m_cooldownUntil, until);
  }

  /**
   * Fail what is still queued. After a refusal the remaining queries of the same burst would only be
   * refused too, so making the caller wait for each one is worse than saying so once.
   */
  void failQueued(std::string reason) {
    while (!m_queue.empty()) {
      const auto task = m_queue.front();
      m_queue.pop_front();
      if (task->finished) continue;
      task->finished = true;
      task->promise.addResult(std::unexpected(reason));
      task->promise.finish();
    }
  }

private:
  struct Task {
    Request request;
    QPromise<Result> promise;
    bool started = false;
    bool finished = false;
  };

  void schedule(qint64 ms) {
    if (m_scheduled) return;
    m_scheduled = true;
    QTimer::singleShot(ms, this, [this] {
      m_scheduled = false;
      pump();
    });
  }

  void pump() {
    if (m_running || m_queue.empty()) return;

    // spacing is measured from the previous start, not from its end: a slow response does not eat
    // into the pause that keeps the next query from looking like a burst
    qint64 wait = 0;
    if (m_last.isValid()) wait = std::max<qint64>(0, SEARCH_INTERVAL_MS - m_last.elapsed());
    if (const qint64 now = QDateTime::currentMSecsSinceEpoch(); now < m_cooldownUntil) {
      wait = std::max(wait, m_cooldownUntil - now);
    }
    if (wait > 0) return schedule(wait);

    const auto task = m_queue.front();
    m_queue.pop_front();
    m_running = true;
    task->started = true;
    m_last.start();

    auto *watcher = new QFutureWatcher<Result>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, task] {
      watcher->deleteLater();
      m_running = false;

      Result result = std::unexpected(std::string("The search did not return a result."));
      try {
        if (watcher->future().resultCount() > 0) result = watcher->result();
      } catch (const std::exception &error) { result = std::unexpected(error.what()); }

      if (!task->finished) {
        task->finished = true;
        task->promise.addResult(std::move(result));
        task->promise.finish();
      }
      pump();
    });
    watcher->setFuture(task->request());
  }

  std::deque<std::shared_ptr<Task>> m_queue;
  QElapsedTimer m_last;
  qint64 m_cooldownUntil = 0;
  bool m_running = false;
  bool m_scheduled = false;
};

} // namespace web_tools

using namespace web_tools;

std::string WebSearchTool::description() const {
  return "Search the web and return result titles, URLs and snippets. Use it for anything that may have "
         "changed after your training data: news, prices, releases, documentation. Then open the most "
         "relevant result with web_fetch to read it. Searches run one at a time with a short pause "
         "between them, so prefer a single well-chosen query over a run of similar ones. Results come "
         "from DuckDuckGo and may be missing or outdated, so always mention the URL you relied on.";
}

std::string WebSearchTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"query":{"type":"string","description":"What to search for, written like a search box query."},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"Maximum number of results. Defaults to 8."}},"required":["query"],"additionalProperties":false})";
}

std::optional<std::string> WebSearchTool::invocationSummary(std::string_view arguments) const {
  SearchInput input;
  if (glz::read_json(input, arguments) || input.query.empty()) return std::nullopt;
  return input.query;
}

AI::AbstractTool::RawToolTask WebSearchTool::runRaw(std::string_view arguments) {
  SearchInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  const auto query = QString::fromStdString(input.query).simplified();
  if (query.isEmpty()) return ready(std::unexpected(std::string("The query must not be empty.")));

  const auto limit = std::clamp(input.limit.value_or(DEFAULT_LIMIT), 1, MAX_LIMIT);

  std::function<void()> cancel;
  auto future = SearchThrottle::instance().submit(
      [query = query.toStdString(), limit]() -> QFuture<AI::AbstractTool::RawToolResult> {
        QUrlQuery form;
        form.addQueryItem(QStringLiteral("q"), QString::fromStdString(query));

        http::Client client;
        client.setUserAgent(QString::fromUtf8(SEARCH_AGENT));
        client.setHeader(QByteArrayLiteral("Referer"), QByteArrayLiteral("https://duckduckgo.com/"));
        client.setHeader(QByteArrayLiteral("Accept-Language"), QByteArrayLiteral("en-US,en;q=0.9"));

        // The HTML endpoints only answer a POST: the same query in the URL is served a challenge page.
        return client.postRaw(QString::fromUtf8(SEARCH_ENDPOINT), form.toString(QUrl::FullyEncoded).toUtf8(),
                              QByteArrayLiteral("application/x-www-form-urlencoded"),
                              {.userAgent = QString::fromUtf8(SEARCH_AGENT)})
            .then([limit](http::Client::Result<QByteArray> response) -> AI::AbstractTool::RawToolResult {
              if (!response) return std::unexpected(response.error());

              const auto page = QString::fromUtf8(*response);
              auto results = parseDuckDuckGo(page);
              results.resize(std::min<std::size_t>(results.size(), limit));

              if (results.empty()) {
                if (page.contains(QLatin1String("anomaly"), Qt::CaseInsensitive)) {
                  auto &throttle = SearchThrottle::instance();
                  const std::string reason =
                      "Web search is paused: DuckDuckGo refused the last request as automated traffic. "
                      "Do not retry in a loop; tell the user, or read a page with web_fetch if you know "
                      "its URL.";
                  throttle.cooldownFor(std::chrono::seconds(30));
                  throttle.failQueued(reason);
                  return std::unexpected(reason);
                }
                return std::unexpected(std::string("No results were found for this query."));
              }

              std::string out;
              if (const auto error = glz::write_json(results, out))
                return std::unexpected(glz::format_error(error));
              return AI::ToolOutput{.content = std::move(out)};
            });
      },
      cancel);

  return {std::move(future), std::move(cancel)};
}

std::string WebFetchTool::description() const {
  return "Open a web page over http or https and return its readable text. Use it after web_search to read "
         "a result, or when the user gives you a URL. Scripts, styling and images are dropped, and long "
         "pages are cut at 64 KiB, so say so when the end is missing instead of guessing the rest.";
}

std::string WebFetchTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"url":{"type":"string","description":"Absolute http or https URL of the page to read."}},"required":["url"],"additionalProperties":false})";
}

std::optional<std::string> WebFetchTool::invocationSummary(std::string_view arguments) const {
  FetchInput input;
  if (glz::read_json(input, arguments)) return std::nullopt;
  return input.url;
}

AI::AbstractTool::RawToolTask WebFetchTool::runRaw(std::string_view arguments) {
  FetchInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  const QUrl url(QString::fromStdString(input.url).trimmed());
  const auto scheme = url.scheme().toLower();

  if (!url.isValid() || url.host().isEmpty())
    return ready(std::unexpected(std::string("The URL is not valid. It must be absolute, such as "
                                             "https://example.com/page.")));
  if (scheme != QLatin1String("http") && scheme != QLatin1String("https"))
    return ready(std::unexpected(std::string("Only http and https URLs can be read.")));
  if (isBlockedHost(url.host()))
    return ready(std::unexpected(std::string("This URL points at the local machine or a private network, "
                                             "which web_fetch does not reach.")));

  http::Client client;
  client.setUserAgent(QString::fromUtf8(FETCH_AGENT));
  client.setHeader(QByteArrayLiteral("Accept"),
                   QByteArrayLiteral("text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8"));
  client.setHeader(QByteArrayLiteral("Accept-Language"), QByteArrayLiteral("en-US,en;q=0.9"));

  auto future = client.getRaw(url.toString(), {.userAgent = QString::fromUtf8(FETCH_AGENT)})
                    .then([pages = url.toString()](http::Client::Result<QByteArray> response)
                              -> AI::AbstractTool::RawToolResult {
                      if (!response) return std::unexpected(response.error());

                      const std::string_view body = {response->constData(),
                                                     static_cast<std::size_t>(response->size())};
                      if (body.empty()) return std::unexpected(std::string("The page returned no content."));
                      if (!AI::isReadableText(body))
                        return std::unexpected(std::string("The page is not text, its content cannot be read."));

                      const bool html = looksLikeHtml(body);
                      const auto text = html ? htmlToText(QString::fromUtf8(body.data(), body.size()))
                                             : QString::fromUtf8(body.data(), body.size());

                      // kept alive: truncated.text points into it
                      const std::string plain = text.toStdString();
                      const auto truncated = AI::truncateUtf8(plain, MAX_OUTPUT_BYTES);

                      std::string out;
                      const FetchResult result{.url = pages.toStdString(),
                                               .content = std::string(truncated.text),
                                               .truncated = truncated.truncated};
                      if (const auto error = glz::write_json(result, out)) {
                        return std::unexpected(glz::format_error(error));
                      }
                      return AI::ToolOutput{.content = std::move(out)};
                    });

  return {std::move(future), {}};
}
