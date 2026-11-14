#pragma once
#include <QCoreApplication>
#include <memory>
#include "builtins/links/links-store.hpp"
#include "builtins/links/links-tools.hpp"
#include "command/command-database.hpp"
#include "command/preference-schema.hpp"
#include "services/ai/tool-policy.hpp"
#include "services/builtin-icon/builtin-icon.hpp"
#include "theme/colors.hpp"

struct LinksPreferences {
  std::string databasePath = LinksStore::defaultPath().string();
};

template <> struct PreferenceSchema<LinksPreferences> {
  Q_DECLARE_TR_FUNCTIONS(LinksPreferences)
public:
  PreferenceMeta databasePath{
      .title = tr("Links database"),
      .description =
          tr("SQLite database of the notes app on this machine. The AI saves, searches and opens the links "
             "in the inbox through it, and the changes reach the hub on the app's next sync."),
      .placeholder = tr("~/.local/share/jotter/jotter_db"),
  };
};

/**
 * The link inbox for the AI chat.
 *
 * Same database the notes extension edits, only the `links` table instead of `notes`: saving a URL here is
 * indistinguishable from pasting it in the app, which is the whole point of going through the file instead
 * of a private copy.
 */
class LinksExtension : public TypedCommandRepository<LinksPreferences> {
  Q_DECLARE_TR_FUNCTIONS(LinksExtension)

  QString id() const override { return QStringLiteral("links"); }
  QString displayName() const override { return tr("Links"); }
  ImageURL iconUrl() const override {
    return ImageURL::builtin(BuiltinIcon::Link).setBackgroundTint(SemanticColor::Blue);
  }

public:
  LinksExtension() : m_store(std::make_shared<LinksStore>()) {
    registerTool(
        {.id = "search",
         .title = tr("Search Links"),
         .description = tr("List and search the saved links."),
         .icon = ImageURL::builtin(BuiltinIcon::MagnifyingGlass).setBackgroundTint(SemanticColor::Blue),
         .defaultPolicy = AI::ToolPolicy::Allow,
         .deferred = false,
         .mcp = false,
         .annotations = {.readOnly = true, .destructive = false, .openWorld = false},
         .create = [store = m_store] { return std::make_unique<LinksSearchTool>(store); }});
    registerTool({.id = "save",
                  .title = tr("Save Link"),
                  .description = tr("Keep a link in the inbox."),
                  .icon = ImageURL::builtin(BuiltinIcon::Bookmark).setBackgroundTint(SemanticColor::Blue),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<LinksSaveTool>(store); }});
    registerTool({.id = "open",
                  .title = tr("Open Link"),
                  .description = tr("Open a saved link in the browser."),
                  .icon = ImageURL::builtin(BuiltinIcon::Globe01).setBackgroundTint(SemanticColor::Blue),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  // Launching the browser is the one step that leaves this machine.
                  .annotations = {.readOnly = false, .destructive = false, .openWorld = true},
                  .create = [store = m_store] { return std::make_unique<LinksOpenTool>(store); }});
    registerTool({.id = "delete",
                  .title = tr("Delete Link"),
                  .description = tr("Remove a link from the inbox."),
                  .icon = ImageURL::builtin(BuiltinIcon::Trash).setBackgroundTint(SemanticColor::Red),
                  .defaultPolicy = AI::ToolPolicy::Ask,
                  .deferred = false,
                  .mcp = false,
                  .annotations = {.readOnly = false, .destructive = true, .openWorld = false},
                  .create = [store = m_store] { return std::make_unique<LinksDeleteTool>(store); }});
  }

  void initialized(const LinksPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

  void preferencesChanged(const LinksPreferences &preferences) const override {
    if (!preferences.databasePath.empty()) m_store->setDatabasePath(preferences.databasePath);
  }

private:
  std::shared_ptr<LinksStore> m_store;
};
