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
 * Access to the notes the desktop app (Jotter) keeps on disk.
 *
 * The app owns this database: it is Room + SQLite, with a WAL and a sync flag. We act as one more editor
 * of the same file, which is the only way to reach the general note — it is device-local by design and
 * never reaches the hub. Every write here mirrors what the app itself would do (bump `updatedTime`, mark
 * `needsSync`), so the next sync pushes the change to the other devices.
 *
 * Notes are never encrypted or decrypted here: an encrypted row is refused, because the password lives
 * with the user and a half-understood note is worse than a clear refusal.
 */
class NotesStore {
public:
  NotesStore();

  /// The app's default location: the XDG data directory, which respects `$XDG_DATA_HOME`.
  static std::filesystem::path defaultPath();

  void setDatabasePath(std::filesystem::path path);
  std::filesystem::path databasePath() const;

  struct Note {
    std::string uuid;
    std::string title;
    std::string content;
    std::string category;
    std::int64_t createdTime = 0;
    std::int64_t updatedTime = 0;
    std::string format = "text";
    bool isTrashed = false;
    bool isEncrypted = false;
    bool isScratchpad = false;
  };

  /// Where the app mirrors the general note, so a wipe can restore it.
  std::filesystem::path recoveryMirrorPath() const;

  std::expected<std::vector<Note>, std::string> search(std::string_view query, int limit,
                                                       bool includeTrashed = false) const;
  std::expected<Note, std::string> read(std::string_view uuid) const;
  std::expected<Note, std::string> readScratchpad() const;

  std::expected<std::string, std::string> create(std::string_view title, std::string_view content,
                                                 std::string_view category) const;
  /// `uuid` may be the pseudo id "geral" to edit the general note.
  std::expected<void, std::string> update(std::string_view uuid, const std::optional<std::string> &title,
                                          const std::optional<std::string> &content,
                                          const std::optional<std::string> &category, bool append) const;
  /// Moves the note to the trash; the app restores it from there.
  std::expected<void, std::string> trash(std::string_view uuid) const;

  /// True when `uuid` names the general note instead of a regular row.
  static bool isGeneralNoteId(std::string_view uuid);

private:
  mutable std::mutex m_mutex;
  std::filesystem::path m_path;
};
