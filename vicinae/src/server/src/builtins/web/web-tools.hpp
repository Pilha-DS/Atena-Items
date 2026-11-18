#pragma once

#include "services/ai/ai-tool.hpp"

/**
 * Web access for the AI chat: one tool searches, the other reads a page. Both return text the
 * model can quote, and neither changes anything on the machine, which is why they are allowed
 * without approval.
 */
class WebSearchTool : public AI::AbstractTool {
public:
  std::string name() const override { return "search"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;
};

class WebFetchTool : public AI::AbstractTool {
public:
  std::string name() const override { return "fetch"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;
};

namespace web_tools {

/**
 * A new question from the user starts: hand the search budget back, so their next question is not
 * charged for the searches of the previous one.
 */
void beginUserTurn();

} // namespace web_tools
