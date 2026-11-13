#include "iamod-actions.hpp"
#include "builtins/ai/quick-ai-view-host.hpp"
#include "builtins/iamod/iamod.hpp"
#include "config/config.hpp"
#include "navigation-controller.hpp"
#include "service-registry.hpp"
#include "services/ai/ai-preferences.hpp"
#include "services/ai/ai-service.hpp"
#include "ui/settings/settings-controller.hpp"
#include "ui/views/intro-view-host.hpp"
#include <utility>

AskIAModAction::AskIAModAction(QString query)
    : AbstractAction(tr("Chat with AI"), IAMod::ICON), m_query(std::move(query)) {}

void AskIAModAction::execute(ApplicationContext *ctx) {
  auto *ai = ctx->services->ai();
  const auto models = ai->listModels(AI::Capability::Completion);

  if (models.empty()) {
    ctx->navigation->pushView(new IntroViewHost(
        tr("Set up an AI provider"),
        tr("Chat with AI needs a language model before it can answer. Add an AI provider in the settings."),
        IAMod::ICON, tr("Open AI Settings"), [ctx]() {
          ctx->settings->openSubpage(QStringLiteral("ai"), QStringLiteral("providers"));
          ctx->navigation->closeWindow();
        }));
    return;
  }

  auto *view = new QuickAIViewHost(*ctx->services, m_query);

  // Without a default model the conversation would wait for a pick before sending the query.
  if (!AI::defaultModel(*ctx->services->config())) {
    view->session()->selectModel(QString::fromStdString(models.front().ref.toString()));
  }

  ctx->navigation->pushView(view);
}

OpenAISettingsAction::OpenAISettingsAction()
    : AbstractAction(tr("AI Settings"), ImageURL::builtin(BuiltinIcon::Cog)) {}

void OpenAISettingsAction::execute(ApplicationContext *ctx) {
  ctx->settings->openSubpage(QStringLiteral("ai"), QStringLiteral("providers"));
  ctx->navigation->closeWindow();
}
