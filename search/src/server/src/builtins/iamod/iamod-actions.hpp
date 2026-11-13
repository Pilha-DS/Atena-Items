#pragma once
#include "ui/action-panel/action.hpp"

/**
 * Opens the inline AI conversation, asking `query` when it is not empty. When no model is
 * configured it guides the user to the AI settings instead of failing silently.
 */
class AskIAModAction : public AbstractAction {
  Q_DECLARE_TR_FUNCTIONS(AskIAModAction)

public:
  explicit AskIAModAction(QString query);

  void execute(ApplicationContext *ctx) override;

private:
  QString m_query;
};

/// Opens the provider page of the AI settings, where the API key is entered.
class OpenAISettingsAction : public AbstractAction {
  Q_DECLARE_TR_FUNCTIONS(OpenAISettingsAction)

public:
  OpenAISettingsAction();

  void execute(ApplicationContext *ctx) override;
};
