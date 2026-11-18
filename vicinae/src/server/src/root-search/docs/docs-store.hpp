#pragma once

#include <filesystem>
#include <mutex>
#include <string>
#include <vector>

/**
 * Read-only view of the Atena Docs index (`docs.db`).
 *
 * The Docs app owns the database. The launcher only lists PRESENT documents so
 * each one can become a root-search item. Missing database → empty list, never
 * an error that would break startup.
 */
class DocsStore {
public:
  struct Document {
    std::string uuid;
    std::string name;
    std::string relPath;
    std::string folder;
    std::vector<std::string> tags;
    std::vector<std::string> aliases;
  };

  DocsStore();

  /// `$XDG_DATA_HOME/docs/docs.db` (falls back to `~/.local/share`).
  static std::filesystem::path defaultDatabasePath();

  /// Same rules as the Docs app: `~/.config/docs/root` or Documents/AtenaData/Docs.
  static std::filesystem::path defaultLibraryRoot();

  void setDatabasePath(std::filesystem::path path);
  void setLibraryRoot(std::filesystem::path path);

  std::filesystem::path databasePath() const;
  std::filesystem::path libraryRoot() const;

  /// Absolute path of a document inside the library, or empty if it cannot be resolved.
  std::filesystem::path absolutePath(const Document &doc) const;

  /// PRESENT documents with explicit tags, folder inheritance and aliases.
  std::vector<Document> listPresent() const;

private:
  mutable std::mutex m_mutex;
  std::filesystem::path m_dbPath;
  std::filesystem::path m_libraryRoot;
};
