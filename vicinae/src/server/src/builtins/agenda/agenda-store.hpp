#pragma once

#include <cstdint>
#include <expected>
#include <filesystem>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

/**
 * Access to the events the Agenda desktop app keeps on disk.
 *
 * Same arrangement as the notes store: the app owns the database (SQLite, one file, one connection per
 * operation), and we act as one more editor of the same file. Every write mirrors what the app would do —
 * bump `updated_time`, set `needs_sync` — so the change shows up in the window and rides the app's own
 * sync to the other devices. A delete also leaves the tombstone the app uses, otherwise the event would
 * be pulled back from the hub on the next sync.
 *
 * Times are epoch milliseconds, like the app stores them.
 */
class AgendaStore {
public:
  AgendaStore();

  /// The app's default location: the XDG data directory, which respects `$XDG_DATA_HOME`.
  static std::filesystem::path defaultPath();

  void setDatabasePath(std::filesystem::path path);
  std::filesystem::path databasePath() const;

  struct Event {
    std::string uuid;
    std::string title;
    std::string description;
    std::string location;
    std::int64_t startTime = 0;
    std::int64_t endTime = 0;
    bool allDay = false;
    std::string color;
    std::int64_t createdTime = 0;
    std::int64_t updatedTime = 0;
  };

  /// A patch leaves what is absent untouched, so an edit never clears a field by accident.
  struct EventPatch {
    std::optional<std::string> title;
    std::optional<std::string> description;
    std::optional<std::string> location;
    std::optional<std::int64_t> startTime;
    std::optional<std::int64_t> endTime;
    std::optional<bool> allDay;
  };

  /// Half-open interval: an event is included when it overlaps `[from, to)`.
  struct Range {
    std::int64_t from = 0;
    std::int64_t to = 0;
  };

  std::expected<std::vector<Event>, std::string> search(const Range &range, std::string_view query,
                                                        int limit) const;
  std::expected<Event, std::string> read(std::string_view uuid) const;

  std::expected<std::string, std::string> create(const Event &event) const;
  std::expected<Event, std::string> update(std::string_view uuid, const EventPatch &patch) const;
  /// Hard delete plus the tombstone, exactly what the app does.
  std::expected<void, std::string> remove(std::string_view uuid) const;

private:
  mutable std::mutex m_mutex;
  std::filesystem::path m_path;
};
