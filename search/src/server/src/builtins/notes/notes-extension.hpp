#pragma once
#include <QCoreApplication>
#include <memory>
#include "builtins/notes/notes-store.hpp"
#include "builtins/notes/notes-tools.hpp"
#include "command/command-database.hpp"
#include "command/preference-schema.hpp"
#include "services/ai/tool-policy.hpp"
#include "services/builtin-icon/builtin-icon.hpp"
#include "theme/colors.hpp"

struct NotesPreferences {
  std::string databasePath = NotesStore::defaultPath().string();
};

template <> struct PreferenceSchema<NotesPreferences> {
  Q_DECLARE_TR_FUNCTIONS(NotesPreferences)
public:
  PreferenceMeta databasePath{
      .title = tr("Notes database"),
      .description =
          tr("SQLite database of the notes app on this machine. The AI reads and writes notes through it, "
             "including the general note, which is local to this device and never reaches the hub."),
      .placeholder = tr("~/.local/share/jotter/jotter_db"),
  };
};

/**
 * Notes for the AI chat.
 *
 * The tools edit the database the desktop app already owns, so the change shows up in the app (after it
 * reloads) and rides the app's own sync to the other devices. Encrypted notes are refused by the store,
 * so there is nothing to decrypt here.
 */
class NotesExtension : public TypedCommandRepository<NotesPreferences> {
  Q_DECLARE_TR_FUNCTIONS(NotesExtension)

  QString id() const override { return QStringLiteral("notes"); }
  QString displayName() const override { return tr("Notes"); }
  ImageURL iconUrl() const override {
    return ImageURL::builtin(BuiltinIcon::BlankDocument).setBackgroundTint(SemanticColor::Green);
  }

public:
  NotesExtension() : m_store(std::make_shared<NotesStore>()) {
    registerTool(
        {.id = "search",
         .title = tr("Search Notes"),
         .description = tr("List and search the user's notes."),
         .icon = ImageURL::builtin(BuiltinIcon::MagnifyingGlass).setBackgroundTint(SemanticColor::Green),
         .defaultPolicy = AI::ToolPolicy::Allow,
         .deferred = false,
         .mcp = false,
         .annotations = {.readOnly = true, .destructive = false, .openWorld = false},
         .create = [store = m_store] { return std::make_unique<NotesSearchTool>(store); }});
    registerTool(
        {.id = "read",
         .title = tr("Read Note"),
         .description = tr("Read a note in full, or the general note."),
         .icon = ImageURL::builtin(BuiltinIcon::BlankDocument).setBackgroundTint(SemanticColor::Green),
         .defaultPolicy = AI::ToolPolicy::Allow,
         .deferred = false,
         .mcp = false,
         .annotations = {.readOnly = true, .destructive = false, .openWorld = false},
         .create = [store = m_store] { return std::make_unique<NotesReadTool>(store); }});
    registerTool({.id = "create",
                  .title = tr("Create Note"),
                  .description = tr("Create a new note."),
                  .icon = ImageURL::builtin(BuiltinIcon::Plus).setBackgroundTint(SemanticColor::Green),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<NotesCreateTool>(store); }});
    registerTool({.id = "update",
                  .title = tr("Edit Note"),
                  .description = tr("Change the title, content or category of a note."),
                  .icon = ImageURL::builtin(BuiltinIcon::Pencil).setBackgroundTint(SemanticColor::Green),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<NotesUpdateTool>(store); }});
    registerTool({.id = "delete",
                  .title = tr("Delete Note"),
                  .description = tr("Move a note to the trash."),
                  .icon = ImageURL::builtin(BuiltinIcon::Trash).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = true, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<NotesDeleteTool>(store); }});
  }

  void initialized(const NotesPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

  void preferencesChanged(const NotesPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

private:
  std::shared_ptr<NotesStore> m_store;
};
