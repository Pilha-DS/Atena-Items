#include "iamod-root-section.hpp"
#include "builtins/iamod/iamod-actions.hpp"
#include "builtins/iamod/iamod.hpp"
#include "builtins/root/root-search-sources.hpp"
#include "config/config.hpp"
#include "service-registry.hpp"
#include "services/ai/ai-preferences.hpp"
#include "services/ai/ai-service.hpp"
#include "theme/theme.hpp"
#include "ui/action-panel/action-panel-state.hpp"

namespace {

QString modelName(AI::Service &service, const AI::ModelRef &ref) {
  for (const auto &model : service.listModels(AI::Capability::Completion)) {
    if (model.ref.provider == ref.provider && model.ref.id == ref.id) {
      return QString::fromStdString(model.name);
    }
  }
  return {};
}

} // namespace

QString IamodRootSection::configuredModelName() const {
  auto *ctx = scope().appContext();
  if (!ctx) return {};

  auto *ai = ctx->services->ai();

  if (auto ref = AI::defaultModel(*ctx->services->config())) {
    if (auto name = modelName(*ai, *ref); !name.isEmpty()) return name;
  }

  // No default model: the row still tells which provider would answer.
  const auto models = ai->listModels(AI::Capability::Completion);
  if (models.empty()) return {};

  if (auto *provider = ai->getProviderById(models.front().ref.provider)) {
    return QString::fromStdString(provider->displayName());
  }

  return {};
}

QString IamodRootSection::itemId(int) const {
  return QString::fromUtf8(IAMod::ROOT_ITEM_ID.data(), static_cast<qsizetype>(IAMod::ROOT_ITEM_ID.size()));
}

QString IamodRootSection::itemTitle(int) const { return tr("Chat with AI"); }

QString IamodRootSection::itemSubtitle(int) const {
  if (!m_query.empty()) return QString::fromStdString(m_query);

  if (auto name = configuredModelName(); !name.isEmpty()) { return tr("Ask %1").arg(name); }

  return tr("Add an AI provider in the settings to start chatting");
}

std::optional<ImageURL> IamodRootSection::itemIcon(int) const { return IAMod::ICON; }

QVariant IamodRootSection::customData(int, int role) const {
  switch (role) {
  case AccessoryText:
    return tr("AI");
  case AccessoryColor:
    return root_search::resolveAccessoryColor(SemanticColor::Accent);
  default:
    return {};
  }
}

std::unique_ptr<ActionPanelState> IamodRootSection::actionPanel(int) const {
  auto panel = std::make_unique<ListActionPanelState>();
  auto *main = panel->createSection();

  auto *ask = new AskIAModAction(QString::fromStdString(m_query));
  ask->setPrimary(true);
  main->addAction(ask);
  main->addAction(new OpenAISettingsAction());

  panel->setTitle(tr("Chat with AI"));
  return panel;
}
