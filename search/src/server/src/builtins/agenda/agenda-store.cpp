#include "agenda-store.hpp"
#include <algorithm>
#include <format>
#include <QDateTime>
#include <QStandardPaths>
#include <QUuid>
#include "internal/db/database.hpp"

namespace {

constexpr int MAX_LIMIT = 200;

constexpr auto COLUMNS =
    "uuid, title, description, location, start_time, end_time, is_all_day, color, created_time, "
    "updated_time";

std::int64_t nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

std::filesystem::path defaultDatabasePath() {
  const auto dataHome = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
  return std::filesystem::path(dataHome.toStdString()) / "agenda" / "agenda.db";
}

AgendaStore::Event readRow(const db::Statement &statement) {
  AgendaStore::Event event;
  event.uuid = statement.columnText(0);
  event.title = statement.columnText(1);
  event.description = statement.columnText(2);
  event.location = statement.columnText(3);
  event.startTime = statement.columnInt64(4);
  event.endTime = statement.columnInt64(5);
  event.allDay = statement.columnInt(6) != 0;
  event.color = statement.columnText(7);
  event.createdTime = statement.columnInt64(8);
  event.updatedTime = statement.columnInt64(9);
  return event;
}

/// `%` and `_` are wildcards in LIKE; a title "50%" must not turn into "everything".
std::string escapeLike(std::string_view value) {
  std::string out;
  out.reserve(value.size() + 8);
  for (char c : value) {
    if (c == '%' || c == '_' || c == '\\') out.push_back('\\');
    out.push_back(c);
  }
  return out;
}

/**
 * A zero-length event still exists in the app (it counts as occupied), and
 * `start_time < end_time` would not match it, so the end is pushed one
 * millisecond forward — the same rule the app's own query applies.
 */
constexpr auto OVERLAPS = "start_time < :to AND "
                          "(CASE WHEN end_time > start_time THEN end_time ELSE start_time + 1 END) > :from";

std::expected<db::Database, std::string> openDatabase(const std::filesystem::path &path) {
  if (!std::filesystem::exists(path))
    return std::unexpected(
        std::format("The agenda database was not found at {}. Is the Agenda app installed?", path.string()));

  auto database = db::Database::openExisting(path);
  if (!database)
    return std::unexpected(std::format("Could not open the agenda database: {}", database.error()));
  return database;
}

std::string notFound(std::string_view uuid) { return std::format("No event has the id {}.", uuid); }

} // namespace

AgendaStore::AgendaStore() : m_path(defaultDatabasePath()) {}

std::filesystem::path AgendaStore::defaultPath() { return defaultDatabasePath(); }

void AgendaStore::setDatabasePath(std::filesystem::path path) {
  if (path.empty()) return;
  std::scoped_lock const lock(m_mutex);
  m_path = std::move(path);
}

std::filesystem::path AgendaStore::databasePath() const {
  std::scoped_lock const lock(m_mutex);
  return m_path;
}

std::expected<std::vector<AgendaStore::Event>, std::string>
AgendaStore::search(const Range &range, std::string_view query, int limit) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  const auto capped = std::clamp(limit, 1, MAX_LIMIT);

  std::string sql = std::format("SELECT {} FROM events WHERE {} ", COLUMNS, OVERLAPS);
  const bool filtered = !query.empty();
  if (filtered)
    sql += "AND (title LIKE :pattern ESCAPE '\\' OR description LIKE :pattern ESCAPE '\\' "
           "OR location LIKE :pattern ESCAPE '\\') ";
  sql += "ORDER BY start_time, is_all_day DESC LIMIT :limit";

  auto statement = database->prepare(sql);
  if (!statement)
    return std::unexpected(std::format("Could not query the events: {}", database->lastError()));

  statement.bind(":from", range.from);
  statement.bind(":to", range.to);
  if (filtered) statement.bind(":pattern", "%" + escapeLike(query) + "%");
  statement.bind(":limit", static_cast<int64_t>(capped));

  std::vector<Event> events;
  while (statement.step())
    events.emplace_back(readRow(statement));

  return events;
}

std::expected<AgendaStore::Event, std::string> AgendaStore::read(std::string_view uuid) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto statement = database->prepare(std::format("SELECT {} FROM events WHERE uuid = :uuid", COLUMNS));
  if (!statement)
    return std::unexpected(std::format("Could not query the events: {}", database->lastError()));

  statement.bind(":uuid", uuid);
  if (!statement.step()) return std::unexpected(notFound(uuid));

  return readRow(statement);
}

std::expected<std::string, std::string> AgendaStore::create(const Event &event) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  // Same shape the app writes for a new event: a fresh uuid, a clock, and the pending-sync flag so the
  // change reaches the hub (and the other devices) on the next sync.
  const auto uuid =
      event.uuid.empty() ? QUuid::createUuid().toString(QUuid::WithoutBraces).toStdString() : event.uuid;
  const auto now = nowMs();

  auto statement = database->prepare(
      "INSERT INTO events(uuid, title, description, location, start_time, end_time, is_all_day, color, "
      "created_time, updated_time, needs_sync) "
      "VALUES(:uuid, :title, :description, :location, :start, :end, :allDay, :color, :now, :now, 1)");
  if (!statement)
    return std::unexpected(std::format("Could not create the event: {}", database->lastError()));

  statement.bind(":uuid", uuid);
  statement.bind(":title", event.title);
  statement.bind(":description", event.description);
  statement.bind(":location", event.location);
  statement.bind(":start", event.startTime);
  statement.bind(":end", event.endTime);
  statement.bind(":allDay", event.allDay ? 1 : 0);
  statement.bind(":color", event.color);
  statement.bind(":now", now);

  if (!statement.exec())
    return std::unexpected(std::format("Could not create the event: {}", database->lastError()));

  return uuid;
}

std::expected<AgendaStore::Event, std::string> AgendaStore::update(std::string_view uuid,
                                                                   const EventPatch &patch) const {
  auto existing = read(uuid);
  if (!existing) return std::unexpected(existing.error());

  if (!patch.title && !patch.description && !patch.location && !patch.startTime && !patch.endTime &&
      !patch.allDay)
    return std::unexpected(
        std::string("Nothing to change: pass at least one of title, description, location, start, end or "
                    "allDay."));

  const auto path = databasePath();
  auto database = openDatabase(path);
  if (!database) return std::unexpected(database.error());

  // A save in the same millisecond as the previous one must still win in last-write-wins, so the
  // timestamp only moves forward.
  const auto updatedTime = std::max(nowMs(), existing->updatedTime + 1);

  std::string sql = "UPDATE events SET updated_time = :updatedTime, needs_sync = 1";
  if (patch.title) sql += ", title = :title";
  if (patch.description) sql += ", description = :description";
  if (patch.location) sql += ", location = :location";
  if (patch.startTime) sql += ", start_time = :start";
  if (patch.endTime) sql += ", end_time = :end";
  if (patch.allDay) sql += ", is_all_day = :allDay";
  sql += " WHERE uuid = :uuid";

  auto statement = database->prepare(sql);
  if (!statement)
    return std::unexpected(std::format("Could not update the event: {}", database->lastError()));

  statement.bind(":updatedTime", updatedTime);
  statement.bind(":uuid", existing->uuid);
  if (patch.title) statement.bind(":title", *patch.title);
  if (patch.description) statement.bind(":description", *patch.description);
  if (patch.location) statement.bind(":location", *patch.location);
  if (patch.startTime) statement.bind(":start", *patch.startTime);
  if (patch.endTime) statement.bind(":end", *patch.endTime);
  if (patch.allDay) statement.bind(":allDay", *patch.allDay ? 1 : 0);

  if (!statement.exec())
    return std::unexpected(std::format("Could not update the event: {}", database->lastError()));

  // Editing an event that had been deleted here brings it back: drop the tombstone, as the app does.
  auto clearTombstone = database->prepare("DELETE FROM tombstones WHERE uuid = :uuid");
  if (clearTombstone) {
    clearTombstone.bind(":uuid", existing->uuid);
    clearTombstone.exec();
  }

  auto updated = *existing;
  if (patch.title) updated.title = *patch.title;
  if (patch.description) updated.description = *patch.description;
  if (patch.location) updated.location = *patch.location;
  if (patch.startTime) updated.startTime = *patch.startTime;
  if (patch.endTime) updated.endTime = *patch.endTime;
  if (patch.allDay) updated.allDay = *patch.allDay;
  updated.updatedTime = updatedTime;
  return updated;
}

std::expected<void, std::string> AgendaStore::remove(std::string_view uuid) const {
  auto existing = read(uuid);
  if (!existing) return std::unexpected(existing.error());

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  // The tombstone is what tells the hub (and the phone) that the event is gone. Without it the next
  // sync would pull the row right back.
  const auto deletedAt = std::max(nowMs(), existing->updatedTime + 1);

  auto deletion = database->prepare("DELETE FROM events WHERE uuid = :uuid");
  if (!deletion) return std::unexpected(std::format("Could not delete the event: {}", database->lastError()));
  deletion.bind(":uuid", existing->uuid);
  if (!deletion.exec())
    return std::unexpected(std::format("Could not delete the event: {}", database->lastError()));

  auto tombstone = database->prepare("INSERT INTO tombstones(uuid, deleted_at) VALUES(:uuid, :deletedAt) "
                                     "ON CONFLICT(uuid) DO UPDATE SET deleted_at = excluded.deleted_at");
  if (!tombstone)
    return std::unexpected(std::format("Could not delete the event: {}", database->lastError()));
  tombstone.bind(":uuid", existing->uuid);
  tombstone.bind(":deletedAt", deletedAt);
  if (!tombstone.exec())
    return std::unexpected(std::format("Could not delete the event: {}", database->lastError()));

  return {};
}
