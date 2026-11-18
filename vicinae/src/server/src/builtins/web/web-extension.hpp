#pragma once
#include <QCoreApplication>
#include "builtins/web/web-tools.hpp"
#include "command/command-database.hpp"
#include "services/ai/tool-policy.hpp"
#include "services/builtin-icon/builtin-icon.hpp"
#include "theme/colors.hpp"

/**
 * Web access for the AI chat. Nothing here runs on its own: the tools only do something when a
 * model calls them.
 */
class WebExtension : public BuiltinCommandRepository {
  Q_DECLARE_TR_FUNCTIONS(WebExtension)

  QString id() const override { return QStringLiteral("web"); }
  QString displayName() const override { return tr("Web"); }
  ImageURL iconUrl() const override {
    return ImageURL::builtin(BuiltinIcon::Network).setBackgroundTint(SemanticColor::Blue);
  }

public:
  WebExtension() {
    registerTool({.id = "search",
                  .title = tr("Web Search"),
                  .description = tr("Search the web and return titles, URLs and snippets."),
                  .icon = ImageURL::builtin(BuiltinIcon::MagnifyingGlass)
                              .setBackgroundTint(SemanticColor::Blue),
                  .defaultPolicy = AI::ToolPolicy::Allow,
                  .deferred = false,
                  .annotations = {.readOnly = true, .destructive = false, .openWorld = true},
                  .create = [] { return std::make_unique<WebSearchTool>(); }});
    registerTool({.id = "fetch",
                  .title = tr("Read Web Page"),
                  .description = tr("Open a URL and return its readable text."),
                  .icon = ImageURL::builtin(BuiltinIcon::BlankDocument)
                              .setBackgroundTint(SemanticColor::Blue),
                  .defaultPolicy = AI::ToolPolicy::Allow,
                  .deferred = false,
                  .annotations = {.readOnly = true, .destructive = false, .openWorld = true},
                  .create = [] { return std::make_unique<WebFetchTool>(); }});
  }
};
