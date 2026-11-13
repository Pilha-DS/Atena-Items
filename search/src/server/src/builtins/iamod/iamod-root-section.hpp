#pragma once
#include <string>
#include <utility>
#include "ui/views/section-source.hpp"

/**
 * The pinned "Chat with AI" row of the root search. It is shown no matter what is being searched,
 * so a question can be asked right away.
 *
 * It is always the default selection, with or without a query: the cursor starts on it and Enter
 * starts a conversation using whatever was typed. The user asked for this, knowing that opening
 * other results then requires moving down the list first.
 */
class IamodRootSection : public SectionSource {
  Q_DECLARE_TR_FUNCTIONS(IamodRootSection)

public:
  QString sectionName() const override { return {}; }
  int count() const override { return m_visible ? 1 : 0; }
  QString itemId(int) const override;
  QString itemTitle(int) const override;
  QString itemSubtitle(int) const override;
  std::optional<ImageURL> itemIcon(int) const override;
  QVariant customData(int i, int role) const override;
  bool isAutoSelectable(int) const override { return true; }
  std::unique_ptr<ActionPanelState> actionPanel(int) const override;

  void setQuery(std::string query) { m_query = std::move(query); }
  void setVisible(bool visible) { m_visible = visible; }

private:
  /// The model that will answer, when one is configured.
  QString configuredModelName() const;

  std::string m_query;
  bool m_visible = true;
};
