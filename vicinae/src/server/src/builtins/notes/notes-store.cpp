#include "notes-store.hpp"
#include <algorithm>
#include <format>
#include <QDateTime>
#include <QFile>
#include <QStandardPaths>
#include <QUuid>
#include "internal/db/database.hpp"

namespace {

constexpr int MAX_LIMIT = 100;
constexpr std::size_t PREVIEW_CHARS = 240;

constexpr auto COLUMNS =
    "uuid, title, content, category, createdTime, updatedTime, format, isTrashed, isEncrypted, isScratchpad";

std::int64_t nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

std::filesystem::path defaultDatabasePath() {
  const auto dataHome = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
  return std::filesystem::path(dataHome.toStdString()) / "jotter" / "jotter_db";
}

NotesStore::Note readRow(const db::Statement &statement) {
  NotesStore::Note note;
  note.uuid = statement.columnText(0);
  note.title = statement.columnText(1);
  note.content = statement.columnText(2);
  note.category = statement.columnText(3);
  note.createdTime = statement.columnInt64(4);
  note.updatedTime = statement.columnInt64(5);
  note.format = statement.columnText(6);
  note.isTrashed = statement.columnInt(7) != 0;
  note.isEncrypted = statement.columnInt(8) != 0;
  note.isScratchpad = statement.columnInt(9) != 0;
  return note;
}

/// `%` and `_` are wildcards in LIKE; a note titled "50%" must not turn into "everything".
std::string escapeLike(std::string_view value) {
  std::string out;
  out.reserve(value.size() + 8);
  for (char c : value) {
    if (c == '%' || c == '_' || c == '\\') out.push_back('\\');
    out.push_back(c);
  }
  return out;
}

std::string encryptedMessage() {
  return "This note is encrypted: its content is only readable with the password in the app, so it cannot be "
         "read or changed here.";
}

std::expected<void, std::string> refuseEncrypted() { return std::unexpected(encryptedMessage()); }

/**
 * The app's category picker is fed by the `categories` table, so a note pointing at a name that is not
 * there would be invisible when the user filters by category. Accept only what already exists.
 */
std::expected<void, std::string> checkCategory(const db::Database &database, std::string_view category) {
  if (category.empty()) return {};

  auto lookup = database.prepare("SELECT 1 FROM categories WHERE name = :name");
  if (!lookup) return std::unexpected("Could not read the categories: " + database.lastError());
  lookup.bind(":name", category);
  if (lookup.step()) return {};

  auto all = database.prepare("SELECT name FROM categories ORDER BY name");
  if (!all) return std::unexpected("Could not read the categories: " + database.lastError());

  std::string known;
  while (all.step()) {
    if (!known.empty()) known += ", ";
    known += all.columnText(0);
  }

  if (known.empty())
    return std::unexpected(std::format("There is no category \"{}\" and none exists yet; leave the category "
                                       "empty.",
                                       category));
  return std::unexpected(
      std::format("There is no category \"{}\". Existing categories: {}.", category, known));
}

/// Opens the app database, or explains why it could not.
std::expected<db::Database, std::string> openDatabase(const std::filesystem::path &path) {
  if (!std::filesystem::exists(path))
    return std::unexpected(
        std::format("The notes database was not found at {}. Is the notes app installed?", path.string()));

  auto database = db::Database::openExisting(path);
  if (!database)
    return std::unexpected(std::format("Could not open the notes database: {}", database.error()));
  return database;
}

/// Cut to at most `maxBytes` without splitting a UTF-8 sequence.
std::string truncateUtf8(std::string_view text, std::size_t maxBytes) {
  if (text.size() <= maxBytes) return std::string(text);
  auto end = maxBytes;
  while (end > 0 && (static_cast<unsigned char>(text[end]) & 0xC0) == 0x80)
    --end;
  return std::string(text.substr(0, end));
}

} // namespace

NotesStore::NotesStore() : m_path(defaultDatabasePath()) {}

std::filesystem::path NotesStore::defaultPath() { return defaultDatabasePath(); }

void NotesStore::setDatabasePath(std::filesystem::path path) {
  if (path.empty()) return;
  std::scoped_lock const lock(m_mutex);
  m_path = std::move(path);
}

std::filesystem::path NotesStore::databasePath() const {
  std::scoped_lock const lock(m_mutex);
  return m_path;
}

std::filesystem::path NotesStore::recoveryMirrorPath() const {
  return databasePath().parent_path() / "scratchpad-recovery.txt";
}

bool NotesStore::isGeneralNoteId(std::string_view uuid) {
  return uuid == "geral" || uuid == "general" || uuid == "scratchpad";
}

std::expected<std::vector<NotesStore::Note>, std::string>
NotesStore::search(std::string_view query, int limit, bool includeTrashed) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  const auto capped = std::clamp(limit, 1, MAX_LIMIT);

  std::string sql = std::format("SELECT {} FROM notes WHERE isEncrypted = 0 AND isScratchpad = 0 ", COLUMNS);
  if (!includeTrashed) sql += "AND isTrashed = 0 AND isArchived = 0 ";

  const bool filtered = !query.empty();
  if (filtered) sql += "AND (title LIKE :pattern ESCAPE '\\' OR content LIKE :pattern ESCAPE '\\') ";
  sql += "ORDER BY updatedTime DESC LIMIT :limit";

  auto statement = database->prepare(sql);
  if (!statement) return std::unexpected(std::format("Could not query the notes: {}", database->lastError()));

  if (filtered) statement.bind(":pattern", "%" + escapeLike(query) + "%");
  statement.bind(":limit", static_cast<int64_t>(capped));

  std::vector<Note> notes;
  while (statement.step())
    notes.emplace_back(readRow(statement));

  // The list view only needs a hint of what the note says; the whole body is one read away.
  for (auto &note : notes) {
    if (note.content.size() > PREVIEW_CHARS) note.content = truncateUtf8(note.content, PREVIEW_CHARS) + "...";
  }

  return notes;
}

std::expected<NotesStore::Note, std::string> NotesStore::read(std::string_view uuid) const {
  if (isGeneralNoteId(uuid)) return readScratchpad();

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto statement = database->prepare(std::format("SELECT {} FROM notes WHERE uuid = :uuid", COLUMNS));
  if (!statement) return std::unexpected(std::format("Could not query the notes: {}", database->lastError()));

  statement.bind(":uuid", uuid);
  if (!statement.step()) return std::unexpected(std::format("No note has the id {}.", uuid));

  auto note = readRow(statement);
  if (note.isEncrypted) return std::unexpected(encryptedMessage());
  return note;
}

std::expected<NotesStore::Note, std::string> NotesStore::readScratchpad() const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto statement = database->prepare(
      std::format("SELECT {} FROM notes WHERE isScratchpad = 1 ORDER BY id ASC LIMIT 1", COLUMNS));
  if (!statement) return std::unexpected(std::format("Could not query the notes: {}", database->lastError()));

  if (!statement.step())
    return std::unexpected(std::string("The general note does not exist yet. Open the notes app once."));

  auto note = readRow(statement);
  if (note.isEncrypted) return std::unexpected(encryptedMessage());
  return note;
}

std::expected<std::string, std::string> NotesStore::create(std::string_view title, std::string_view content,
                                                           std::string_view category) const {
  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  if (auto known = checkCategory(*database, category); !known) return std::unexpected(known.error());

  // Same shape the app writes for a new note: a fresh uuid, a clock, and the pending-sync flag so the
  // change reaches the hub (and the other devices) on the next sync.
  const auto uuid = QUuid::createUuid().toString(QUuid::WithoutBraces).toStdString();
  const auto now = nowMs();

  auto statement = database->prepare(
      "INSERT INTO notes (uuid, title, content, category, createdTime, updatedTime, isPinned, isLocked, "
      "isArchived, isTrashed, isEncrypted, isScratchpad, format, needsSync) "
      "VALUES (:uuid, :title, :content, :category, :now, :now, 0, 0, 0, 0, 0, 0, 'text', 1)");
  if (!statement) return std::unexpected(std::format("Could not create the note: {}", database->lastError()));

  statement.bind(":uuid", uuid);
  statement.bind(":title", title);
  statement.bind(":content", content);
  statement.bind(":category", category);
  statement.bind(":now", static_cast<int64_t>(now));

  if (!statement.exec())
    return std::unexpected(std::format("Could not create the note: {}", database->lastError()));

  return uuid;
}

std::expected<void, std::string> NotesStore::update(std::string_view uuid,
                                                    const std::optional<std::string> &title,
                                                    const std::optional<std::string> &content,
                                                    const std::optional<std::string> &category,
                                                    bool append) const {
  auto note = read(uuid);
  if (!note) return std::unexpected(note.error());
  if (note->isEncrypted) return refuseEncrypted();

  if (!title && !content && !category)
    return std::unexpected(
        std::string("Nothing to change: pass at least one of title, content or category."));
  if (append && !content)
    return std::unexpected(std::string("append only makes sense together with content."));

  const auto path = databasePath();
  auto database = openDatabase(path);
  if (!database) return std::unexpected(database.error());

  if (category && !category->empty()) {
    if (auto known = checkCategory(*database, *category); !known) return std::unexpected(known.error());
  }

  const auto now = nowMs();
  std::string sql = "UPDATE notes SET updatedTime = :now";
  // The app's sync only looks at rows flagged as changed; without this the edit stays on this machine.
  if (!note->isScratchpad) sql += ", needsSync = 1";
  if (title) sql += ", title = :title";
  if (content) sql += append ? ", content = content || char(10) || :content" : ", content = :content";
  if (category) sql += ", category = :category";
  sql += " WHERE uuid = :uuid";

  auto statement = database->prepare(sql);
  if (!statement) return std::unexpected(std::format("Could not update the note: {}", database->lastError()));

  statement.bind(":now", static_cast<int64_t>(now));
  // `uuid` may be the "geral" alias, so the resolved row is the one to target.
  statement.bind(":uuid", note->uuid);
  if (title) statement.bind(":title", *title);
  if (content) statement.bind(":content", *content);
  if (category) statement.bind(":category", *category);

  if (!statement.exec())
    return std::unexpected(std::format("Could not update the note: {}", database->lastError()));

  // The general note has a plain-text mirror beside the database, and the app restores from it whenever
  // the row comes back empty. Leaving it untouched would resurrect what the user just replaced.
  if (note->isScratchpad && content) {
    const auto mirror = path.parent_path() / "scratchpad-recovery.txt";
    if (content->empty()) {
      QFile::remove(QString::fromStdString(mirror.string()));
    } else {
      QFile mirrorFile(QString::fromStdString(mirror.string()));
      if (mirrorFile.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        mirrorFile.write(QByteArray::fromStdString(*content));
        mirrorFile.close();
      }
    }
  }

  return {};
}

std::expected<void, std::string> NotesStore::trash(std::string_view uuid) const {
  if (isGeneralNoteId(uuid))
    return std::unexpected(std::string("The general note cannot be deleted. Clear its content instead."));

  auto note = read(uuid);
  if (!note) return std::unexpected(note.error());
  if (note->isEncrypted) return refuseEncrypted();
  if (note->isTrashed) return std::unexpected(std::string("This note is already in the trash."));

  auto database = openDatabase(databasePath());
  if (!database) return std::unexpected(database.error());

  auto statement = database->prepare(
      "UPDATE notes SET isTrashed = 1, needsSync = 1, updatedTime = :now WHERE uuid = :uuid");
  if (!statement) return std::unexpected(std::format("Could not delete the note: {}", database->lastError()));

  statement.bind(":now", static_cast<int64_t>(nowMs()));
  statement.bind(":uuid", uuid);

  if (!statement.exec())
    return std::unexpected(std::format("Could not delete the note: {}", database->lastError()));

  return {};
}
