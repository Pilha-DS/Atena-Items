#include "root-search/docs/docs-store.hpp"

#include "internal/db/database.hpp"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QIODevice>
#include <QStandardPaths>
#include <QString>
#include <QTextStream>

#include <algorithm>
#include <unordered_map>
#include <unordered_set>

namespace {

std::filesystem::path xdgDataHome() {
  const auto data = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
  return std::filesystem::path(data.toStdString());
}

std::filesystem::path xdgConfigHome() {
  const auto config = QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation);
  return std::filesystem::path(config.toStdString());
}

std::filesystem::path expandHomePath(const QString &raw) {
  QString trimmed = raw.trimmed();
  if (trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.size() >= 2) {
    trimmed = trimmed.mid(1, trimmed.size() - 2);
  }
  if (trimmed.isEmpty()) return {};

  const QString home = QDir::homePath();
  QString expanded = trimmed;
  if (expanded == QLatin1String("$HOME") || expanded == QLatin1String("~")) {
    expanded = home;
  } else if (expanded.startsWith(QLatin1String("$HOME/"))) {
    expanded = home + expanded.mid(5);
  } else if (expanded.startsWith(QLatin1String("~/"))) {
    expanded = home + expanded.mid(1);
  }

  const QFileInfo info(expanded);
  if (!info.isAbsolute()) return {};
  return std::filesystem::path(info.absoluteFilePath().toStdString());
}

std::filesystem::path readDocsRootOverride() {
  const auto file = xdgConfigHome() / "docs" / "root";
  QFile qfile(QString::fromStdString(file.string()));
  if (!qfile.open(QIODevice::ReadOnly | QIODevice::Text)) return {};
  QTextStream in(&qfile);
  return expandHomePath(in.readAll());
}

std::filesystem::path defaultDocsLibraryRoot() {
  if (const auto override = readDocsRootOverride(); !override.empty()) return override;

  const auto documents = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
  return std::filesystem::path(documents.toStdString()) / "AtenaData" / "Docs";
}

std::string folderOf(std::string_view relPath) {
  const auto slash = relPath.find_last_of('/');
  if (slash == std::string_view::npos || slash == 0) return {};
  return std::string(relPath.substr(0, slash));
}

std::string tagLeaf(std::string_view path) {
  const auto slash = path.find_last_of('/');
  if (slash == std::string_view::npos) return std::string(path);
  return std::string(path.substr(slash + 1));
}

/// Outermost ancestor first, then the folder itself — same order as Docs PathTree.
std::vector<std::string> folderChain(const std::string &folder) {
  if (folder.empty()) return {};
  std::vector<std::string> chain;
  std::size_t cut = folder.find('/');
  while (cut != std::string::npos) {
    chain.emplace_back(folder.substr(0, cut));
    cut = folder.find('/', cut + 1);
  }
  chain.push_back(folder);
  return chain;
}

std::vector<std::string> inheritTags(const std::string &folder,
                                     const std::unordered_map<std::string, std::vector<std::string>> &byFolder) {
  if (folder.empty() || byFolder.empty()) return {};
  std::vector<std::string> out;
  std::unordered_set<std::string> seen;
  for (const auto &part : folderChain(folder)) {
    const auto it = byFolder.find(part);
    if (it == byFolder.end()) continue;
    for (const auto &tag : it->second) {
      if (seen.insert(tag).second) out.push_back(tag);
    }
  }
  return out;
}

} // namespace

DocsStore::DocsStore() : m_dbPath(defaultDatabasePath()), m_libraryRoot(defaultLibraryRoot()) {}

std::filesystem::path DocsStore::defaultDatabasePath() { return xdgDataHome() / "docs" / "docs.db"; }

std::filesystem::path DocsStore::defaultLibraryRoot() { return defaultDocsLibraryRoot(); }

void DocsStore::setDatabasePath(std::filesystem::path path) {
  if (path.empty()) return;
  std::scoped_lock const lock(m_mutex);
  m_dbPath = std::move(path);
}

void DocsStore::setLibraryRoot(std::filesystem::path path) {
  if (path.empty()) return;
  std::scoped_lock const lock(m_mutex);
  m_libraryRoot = std::move(path);
}

std::filesystem::path DocsStore::databasePath() const {
  std::scoped_lock const lock(m_mutex);
  return m_dbPath;
}

std::filesystem::path DocsStore::libraryRoot() const {
  std::scoped_lock const lock(m_mutex);
  return m_libraryRoot;
}

std::filesystem::path DocsStore::absolutePath(const Document &doc) const {
  if (doc.relPath.empty()) return {};
  const auto root = libraryRoot();
  if (root.empty()) return {};
  return root / doc.relPath;
}

std::vector<DocsStore::Document> DocsStore::listPresent() const {
  const auto path = databasePath();
  if (!std::filesystem::exists(path)) return {};

  auto database = db::Database::openReadOnly(path);
  if (!database) return {};

  std::vector<Document> docs;
  {
    auto statement = database->prepare(
        "SELECT uuid, name, relPath FROM documents WHERE status = 'PRESENT' ORDER BY name COLLATE NOCASE");
    if (!statement) return {};
    while (statement.step()) {
      Document doc;
      doc.uuid = statement.columnText(0);
      doc.name = statement.columnText(1);
      doc.relPath = statement.columnText(2);
      doc.folder = folderOf(doc.relPath);
      docs.emplace_back(std::move(doc));
    }
  }

  if (docs.empty()) return docs;

  std::unordered_map<std::string, std::size_t> byUuid;
  byUuid.reserve(docs.size());
  for (std::size_t i = 0; i < docs.size(); ++i)
    byUuid.emplace(docs[i].uuid, i);

  // Explicit document tags.
  {
    auto statement = database->prepare(
        "SELECT d.uuid, t.path FROM documents d "
        "JOIN doc_tags dt ON dt.docId = d.id "
        "JOIN tags t ON t.id = dt.tagId "
        "WHERE d.status = 'PRESENT'");
    if (statement) {
      while (statement.step()) {
        const auto uuid = statement.columnText(0);
        const auto tag = statement.columnText(1);
        const auto it = byUuid.find(uuid);
        if (it == byUuid.end()) continue;
        docs[it->second].tags.push_back(tag);
      }
    }
  }

  // Folder → tags for inheritance.
  std::unordered_map<std::string, std::vector<std::string>> byFolder;
  {
    auto statement = database->prepare(
        "SELECT ft.folderPath, t.path FROM folder_tags ft JOIN tags t ON t.id = ft.tagId");
    if (statement) {
      while (statement.step()) {
        byFolder[statement.columnText(0)].push_back(statement.columnText(1));
      }
    }
  }

  if (!byFolder.empty()) {
    for (auto &doc : docs) {
      for (const auto &tag : inheritTags(doc.folder, byFolder)) {
        if (std::ranges::find(doc.tags, tag) == doc.tags.end()) doc.tags.push_back(tag);
      }
    }
  }

  // Aliases keyed by tag path.
  std::unordered_map<std::string, std::vector<std::string>> aliasesByTag;
  {
    auto statement = database->prepare(
        "SELECT t.path, a.alias FROM tag_aliases a JOIN tags t ON t.id = a.tagId");
    if (statement) {
      while (statement.step()) {
        aliasesByTag[statement.columnText(0)].push_back(statement.columnText(1));
      }
    }
  }

  for (auto &doc : docs) {
    std::unordered_set<std::string> seen;
    for (const auto &tag : doc.tags) {
      const auto it = aliasesByTag.find(tag);
      if (it == aliasesByTag.end()) continue;
      for (const auto &alias : it->second) {
        if (seen.insert(alias).second) doc.aliases.push_back(alias);
      }
    }
    // Also index the leaf name of each tag path for search keywords.
    for (const auto &tag : doc.tags) {
      const auto leaf = tagLeaf(tag);
      if (leaf != tag && seen.insert(leaf).second) doc.aliases.push_back(leaf);
    }
  }

  return docs;
}
