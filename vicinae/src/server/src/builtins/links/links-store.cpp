#include "links-store.hpp"
#include <algorithm>
#include <cctype>
#include <format>
#include <QDateTime>
#include <QStandardPaths>
#include <QUuid>
#include "internal/db/database.hpp"

namespace {

constexpr int MAX_LIMIT = 100;

constexpr auto COLUMNS = "uuid, url, title, createdTime, updatedTime, lastOpenedTime, openCount";

std::int64_t nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

std::filesystem::path defaultDatabasePath() {
  const auto dataHome = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
  return std::filesystem::path(dataHome.toStdString()) / "jotter" / "jotter_db";
}

LinksStore::Link readRow(const db::Statement &statement) {
  LinksStore::Link link;
  link.uuid = statement.columnText(0);
  link.url = statement.columnText(1);
  link.title = statement.columnText(2);
  link.createdTime = statement.columnInt64(3);
  link.updatedTime = statement.columnInt64(4);
  link.lastOpenedTime = statement.columnInt64(5);
  link.openCount = statement.columnInt(6);
  return link;
}

/// `%` and `_` are wildcards in LIKE; a link titled "50%" must not turn into "everything".
std::string escapeLike(std::string_view value) {
  std::string out;
  out.reserve(value.size() + 8);
  for (char c : value) {
    if (c == '%' || c == '_' || c == '\\') out.push_back('\\');
    out.push_back(c);
  }
  return out;
}

/// Opens the app database, or explains why it could not.
std::expected<db::Database, std::string> openDatabase(const std::filesystem::path &path) {
  if (!std::filesystem::exists(path))
    return std::unexpected(
        std::format("The notes database was not found at {}. Is the notes app installed?", path.string()));

  auto database = db::Database::openExisting(path);
  if (!database) return std::unexpected(std::format("Could not open the notes database: {}", database.error()));
  return database;
}

bool isSpace(char c) { return std::isspace(static_cast<unsigned char>(c)) != 0; }

std::string trim(std::string_view value) {
  while (!value.empty() && isSpace(value.front()))
    value.remove_prefix(1);
  while (!value.empty() && isSpace(value.back()))
    value.remove_suffix(1);
  return std::string(value);
}

std::string toLower(std::string_view value) {
  std::string out(value);
  std::transform(out.begin(), out.end(), out.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return out;
}

bool isDigits(std::string_view value) {
  return !value.empty() &&
         std::all_of(value.begin(), value.end(), [](char c) { return std::isdigit(static_cast<unsigned char>(c)); });
}

std::string notFound(std::string_view uuid) { return std::format("No link has the id {}.", uuid); }

} // namespace

LinksStore::LinksStore() : m_path(defaultDatabasePath()) {}

std::filesystem::path LinksStore::defaultPath() { return defaultDatabasePath(); }

void LinksStore::setDatabasePath(std::filesystem::path path) {
  if (path.empty()) return;
  std::scoped_lock const lock(m_mutex);
  m_path = std::move(path);
}

std::filesystem::path LinksStore::databasePath() const {
  std::scoped_lock const lock(m_mutex);
  return m_path;
}

std::optional<std::string> LinksStore::normalizeUrl(std::string_view raw) {
  std::string value = trim(raw);
  // A pasted URL often arrives wrapped in angle brackets: <https://x>.
  if (value.size() > 2 && value.front() == '<' && value.back() == '>')
    value = trim(std::string_view(value).substr(1, value.size() - 2));
  if (value.empty()) return std::nullopt;
  if (std::any_of(value.begin(), value.end(), isSpace)) return std::nullopt;

  if (value.find("://") == std::string::npos) {
    // Without a scheme there has to be a host that looks like one, so a sentence typed by accident is not
    // stored as a link.
    const auto head = value.substr(0, value.find('/'));
    if (head.empty()) return std::nullopt;
    if (head.find('.') == std::string::npos && head.find("localhost") == std::string::npos)
      return std::nullopt;
    value = "https://" + value;
  }

  const auto schemeEnd = value.find("://");
  const auto scheme = toLower(std::string_view(value).substr(0, schemeEnd));
  if (scheme != "http" && scheme != "https") return std::nullopt;

  std::string_view rest(value);
  rest.remove_prefix(schemeEnd + 3);

  // The authority ends at the first path, query or fragment separator.
  const auto authorityEnd = rest.find_first_of("/?#");
  const auto authority = rest.substr(0, authorityEnd);
  std::string_view remainder = authorityEnd == std::string_view::npos ? std::string_view{} : rest.substr(authorityEnd);

  // user:password@host:port — the userinfo is kept verbatim, dots inside it are the caller's business.
  std::string userInfo;
  std::string_view hostPort = authority;
  if (const auto at = authority.rfind('@'); at != std::string_view::npos) {
    userInfo = std::string(authority.substr(0, at + 1));
    hostPort = authority.substr(at + 1);
  }

  std::string_view host = hostPort;
  std::string_view port;
  if (!hostPort.empty() && hostPort.front() == '[') {
    // IPv6 literal: the host is everything up to the closing bracket.
    const auto close = hostPort.find(']');
    if (close == std::string_view::npos) return std::nullopt;
    host = hostPort.substr(0, close + 1);
    if (close + 1 < hostPort.size()) {
      if (hostPort[close + 1] != ':') return std::nullopt;
      port = hostPort.substr(close + 2);
    }
  } else if (const auto colon = hostPort.rfind(':'); colon != std::string_view::npos) {
    host = hostPort.substr(0, colon);
    port = hostPort.substr(colon + 1);
  }

  std::string cleanHost = toLower(host);
  while (!cleanHost.empty() && cleanHost.back() == '.')
    cleanHost.pop_back();
  if (cleanHost.empty()) return std::nullopt;
  // A host without a dot is only legal for localhost and IPv6 literals.
  if (cleanHost.find('.') == std::string::npos && cleanHost.find(':') == std::string::npos &&
      cleanHost != "localhost")
    return std::nullopt;

  std::string portSuffix;
  if (!port.empty()) {
    // A port that overflows is not a port; refuse instead of guessing.
    if (!isDigits(port) || port.size() > 5) return std::nullopt;
    const int number = std::stoi(std::string(port));
    const int defaultPort = scheme == "http" ? 80 : 443;
    if (number != defaultPort) portSuffix = ":" + std::to_string(number);
  }

  std::string fragment;
  if (const auto hash = remainder.find('#'); hash != std::string_view::npos) {
    fragment = std::string(remainder.substr(hash));
    remainder = remainder.substr(0, hash);
  }
  std::string query;
  if (const auto mark = remainder.find('?'); mark != std::string_view::npos) {
    query = std::string(remainder.substr(mark));
    remainder = remainder.substr(0, mark);
  }
  // A bare trailing slash is noise: "example.com/" and "example.com" are the same page.
  const std::string path = remainder == "/" ? std::string() : std::string(remainder);

  std::string out;
  out.reserve(value.size());
  out += scheme;
  out += "://";
  out += userInfo;
  out += cleanHost;
  out += portSuffix;
  out += path;
  out += query;
  out += fragment;
  return out;
}

std::expected<std::vector<LinksStore::Link>, std::string> LinksStore::search(std::string_view query,
                                                                             int limit) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  const auto capped = std::clamp(limit, 1, MAX_LIMIT);

  // `MAX(x, y)` is the two-argument SQLite function, not the aggregate: a link opened today beats one saved
  // months ago, and a link just saved is visible without an extra tap. Same order as the app's list.
  std::string sql = std::format("SELECT {} FROM links WHERE 1 = 1 ", COLUMNS);
  const bool filtered = !query.empty();
  if (filtered) sql += "AND (title LIKE :pattern ESCAPE '\\' OR url LIKE :pattern ESCAPE '\\') ";
  sql += "ORDER BY MAX(lastOpenedTime, createdTime) DESC, id DESC LIMIT :limit";

  auto statement = database->prepare(sql);
  if (!statement) return std::unexpected(std::format("Could not query the links: {}", database->lastError()));

  if (filtered) statement.bind(":pattern", "%" + escapeLike(query) + "%");
  statement.bind(":limit", static_cast<int64_t>(capped));

  std::vector<Link> links;
  while (statement.step())
    links.emplace_back(readRow(statement));

  return links;
}

std::expected<LinksStore::Link, std::string> LinksStore::read(std::string_view uuid) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto statement = database->prepare(std::format("SELECT {} FROM links WHERE uuid = :uuid", COLUMNS));
  if (!statement) return std::unexpected(std::format("Could not query the links: {}", database->lastError()));

  statement.bind(":uuid", uuid);
  if (!statement.step()) return std::unexpected(notFound(uuid));

  return readRow(statement);
}

std::expected<LinksStore::Saved, std::string> LinksStore::save(std::string_view rawUrl,
                                                               std::string_view title) const {
  const auto url = normalizeUrl(rawUrl);
  if (!url)
    return std::unexpected(
        std::format("\"{}\" is not an http(s) link. Pass the full address, such as https://example.com/page.",
                    rawUrl));

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  const std::string cleanTitle = trim(title);
  const auto now = nowMs();

  auto lookup = database->prepare(std::format("SELECT {} FROM links WHERE url = :url LIMIT 1", COLUMNS));
  if (!lookup) return std::unexpected(std::format("Could not query the links: {}", database->lastError()));
  lookup.bind(":url", *url);

  if (lookup.step()) {
    auto existing = readRow(lookup);
    // Saving the same page again is a "bring it back up", not a duplicate: the row keeps its identity and
    // takes the new label when one was given. The timestamp only moves forward, so a save in the same
    // millisecond as the previous one still wins in last-write-wins.
    const auto updatedTime = std::max(now, existing.updatedTime + 1);
    const std::string finalTitle = cleanTitle.empty() ? existing.title : cleanTitle;

    auto statement =
        database->prepare("UPDATE links SET title = :title, updatedTime = :updatedTime, needsSync = 1 "
                          "WHERE uuid = :uuid");
    if (!statement)
      return std::unexpected(std::format("Could not update the link: {}", database->lastError()));

    statement.bind(":title", finalTitle);
    statement.bind(":updatedTime", updatedTime);
    statement.bind(":uuid", existing.uuid);
    if (!statement.exec())
      return std::unexpected(std::format("Could not update the link: {}", database->lastError()));

    existing.title = finalTitle;
    existing.updatedTime = updatedTime;
    return Saved{.link = existing, .created = false};
  }

  // Same shape the app writes for a new link: a fresh uuid, a clock, and the pending-sync flag so the change
  // reaches the hub (and the other devices) on the next sync.
  const auto uuid = QUuid::createUuid().toString(QUuid::WithoutBraces).toStdString();

  auto statement = database->prepare(
      "INSERT INTO links (url, title, createdTime, updatedTime, lastOpenedTime, openCount, uuid, needsSync) "
      "VALUES (:url, :title, :now, :now, 0, 0, :uuid, 1)");
  if (!statement) return std::unexpected(std::format("Could not save the link: {}", database->lastError()));

  statement.bind(":url", *url);
  statement.bind(":title", cleanTitle);
  statement.bind(":now", static_cast<int64_t>(now));
  statement.bind(":uuid", uuid);

  if (!statement.exec()) return std::unexpected(std::format("Could not save the link: {}", database->lastError()));

  return Saved{
      .link =
          Link{
              .uuid = uuid,
              .url = *url,
              .title = cleanTitle,
              .createdTime = now,
              .updatedTime = now,
              .lastOpenedTime = 0,
              .openCount = 0,
          },
      .created = true,
  };
}

std::expected<LinksStore::Link, std::string> LinksStore::markOpened(std::string_view uuid) const {
  auto existing = read(uuid);
  if (!existing) return std::unexpected(existing.error());

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  const auto now = std::max(nowMs(), existing->updatedTime + 1);

  auto statement = database->prepare("UPDATE links SET lastOpenedTime = :now, updatedTime = :now, "
                                     "openCount = openCount + 1, needsSync = 1 WHERE uuid = :uuid");
  if (!statement) return std::unexpected(std::format("Could not update the link: {}", database->lastError()));

  statement.bind(":now", static_cast<int64_t>(now));
  statement.bind(":uuid", existing->uuid);
  if (!statement.exec())
    return std::unexpected(std::format("Could not update the link: {}", database->lastError()));

  existing->lastOpenedTime = now;
  existing->updatedTime = now;
  existing->openCount += 1;
  return *existing;
}

std::expected<void, std::string> LinksStore::remove(std::string_view uuid) const {
  auto existing = read(uuid);
  if (!existing) return std::unexpected(existing.error());

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto transaction = database->transaction();

  // Without the tombstone the next sync would pull the row back from the hub, and the link the user just
  // deleted would reappear on every device.
  auto tombstone = database->prepare("INSERT OR REPLACE INTO deleted_links (uuid, deletedAt) VALUES (:uuid, :now)");
  if (!tombstone) return std::unexpected(std::format("Could not delete the link: {}", database->lastError()));
  tombstone.bind(":uuid", existing->uuid);
  tombstone.bind(":now", static_cast<int64_t>(nowMs()));
  if (!tombstone.exec())
    return std::unexpected(std::format("Could not delete the link: {}", database->lastError()));

  auto removal = database->prepare("DELETE FROM links WHERE uuid = :uuid");
  if (!removal) return std::unexpected(std::format("Could not delete the link: {}", database->lastError()));
  removal.bind(":uuid", existing->uuid);
  if (!removal.exec() || database->changes() == 0)
    return std::unexpected(std::format("Could not delete the link: {}", database->lastError()));

  if (!transaction.commit())
    return std::unexpected(std::format("Could not delete the link: {}", database->lastError()));

  return {};
}
