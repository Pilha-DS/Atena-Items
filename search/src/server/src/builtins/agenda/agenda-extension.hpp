#pragma once
#include <QCoreApplication>
#include <memory>
#include "builtins/agenda/agenda-store.hpp"
#include "builtins/agenda/agenda-tools.hpp"
#include "command/command-database.hpp"
#include "command/preference-schema.hpp"
#include "services/ai/tool-policy.hpp"
#include "services/builtin-icon/builtin-icon.hpp"
#include "theme/colors.hpp"

struct AgendaPreferences {
  std::string databasePath = AgendaStore::defaultPath().string();
};

template <> struct PreferenceSchema<AgendaPreferences> {
  Q_DECLARE_TR_FUNCTIONS(AgendaPreferences)
public:
  PreferenceMeta databasePath{
      .title = tr("Agenda database"),
      .description =
          tr("SQLite database of the Agenda app on this machine. The AI reads and writes events through "
             "it, and the changes reach the other devices on the app's next sync."),
      .placeholder = tr("~/.local/share/agenda/agenda.db"),
  };
};

/**
 * Events for the AI chat.
 *
 * The tools edit the database the desktop app already owns, so a created event shows up in the window
 * and rides the app's own sync to the phone. The delete leaves the tombstone the app expects, otherwise
 * the next sync would bring the event back.
 */
class AgendaExtension : public TypedCommandRepository<AgendaPreferences> {
  Q_DECLARE_TR_FUNCTIONS(AgendaExtension)

  QString id() const override { return QStringLiteral("agenda"); }
  QString displayName() const override { return tr("Agenda"); }
  ImageURL iconUrl() const override {
    return ImageURL::builtin(BuiltinIcon::Calendar).setBackgroundTint(SemanticColor::Red);
  }

public:
  AgendaExtension() : m_store(std::make_shared<AgendaStore>()) {
    registerTool(
        {.id = "search",
         .title = tr("Search Events"),
         .description = tr("List the calendar events in a time range."),
         .icon = ImageURL::builtin(BuiltinIcon::MagnifyingGlass).setBackgroundTint(SemanticColor::Red),
         .defaultPolicy = AI::ToolPolicy::Allow,
         .deferred = false,
         .mcp = false,
         .annotations = {.readOnly = true, .destructive = false, .openWorld = false},
         .create = [store = m_store] { return std::make_unique<AgendaSearchTool>(store); }});
    registerTool({.id = "read",
                  .title = tr("Read Event"),
                  .description = tr("Read one event in full."),
                  .icon = ImageURL::builtin(BuiltinIcon::Calendar).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Allow,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = true, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<AgendaReadTool>(store); }});
    registerTool({.id = "create",
                  .title = tr("Create Event"),
                  .description = tr("Create a new calendar event."),
                  .icon = ImageURL::builtin(BuiltinIcon::Plus).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<AgendaCreateTool>(store); }});
    registerTool({.id = "update",
                  .title = tr("Edit Event"),
                  .description = tr("Change the title, time, place or notes of an event."),
                  .icon = ImageURL::builtin(BuiltinIcon::Pencil).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<AgendaUpdateTool>(store); }});
    registerTool({.id = "delete",
                  .title = tr("Delete Event"),
                  .description = tr("Delete an event from the calendar."),
                  .icon = ImageURL::builtin(BuiltinIcon::Trash).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = true, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<AgendaDeleteTool>(store); }});
  }

  void initialized(const AgendaPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

  void preferencesChanged(const AgendaPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

private:
  std::shared_ptr<AgendaStore> m_store;
};
