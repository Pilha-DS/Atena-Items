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
 * The link inbox the notes app (Jotter) keeps on disk.
 *
 * It is the same SQLite file the app owns — `links` for the inbox itself and `deleted_links` for the
 * tombstones the sync needs. We act as one more editor of that file: every write mirrors what the app
 * would do (bump `updatedTime`, mark `needsSync`, and leave a tombstone when a row is removed), so the
 * change reaches the hub and the other devices on the next sync.
 *
 * URLs are always stored in the canonical form the app uses, so a link saved here and the same link pasted
 * in the app are one row instead of two.
 */
class LinksStore {
public:
  LinksStore();

  /// The app's default location: the XDG data directory, which respects `$XDG_DATA_HOME`.
  static std::filesystem::path defaultPath();

  void setDatabasePath(std::filesystem::path path);
  std::filesystem::path databasePath() const;

  struct Link {
    std::string uuid;
    std::string url;
    std::string title;
    std::int64_t createdTime = 0;
    std::int64_t updatedTime = 0;
    std::int64_t lastOpenedTime = 0;
    int openCount = 0;
  };

  struct Saved {
    Link link;
    /// False when the page was already in the inbox and the row was refreshed instead.
    bool created = true;
  };

  /// The inbox as the app lists it: whatever was saved or opened most recently first.
  std::expected<std::vector<Link>, std::string> search(std::string_view query, int limit) const;
  std::expected<Link, std::string> read(std::string_view uuid) const;

  /**
   * Stores a link, normalizing the URL first. Saving the same page again is a refresh, not a duplicate: the
   * row keeps its id and takes the new label when one is given.
   */
  std::expected<Saved, std::string> save(std::string_view rawUrl, std::string_view title) const;

  /// Ranks the link as opened, which is what puts it on top of the list on every device.
  std::expected<Link, std::string> markOpened(std::string_view uuid) const;

  /// Hard delete plus the tombstone the app expects, so the other devices drop the row too.
  std::expected<void, std::string> remove(std::string_view uuid) const;

  /**
   * The canonical form the app stores: scheme and host lowercased, default port dropped, a bare trailing
   * slash removed. Returns `nullopt` when the text is not an http(s) link.
   */
  static std::optional<std::string> normalizeUrl(std::string_view raw);

private:
  mutable std::mutex m_mutex;
  std::filesystem::path m_path;
};
