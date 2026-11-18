#pragma once

#include <memory>
#include <string>
#include "links-store.hpp"
#include "services/ai/ai-tool.hpp"

/**
 * Link tools for the AI chat: list, save, open and delete.
 *
 * These are the four moves the inbox exists for. Opening is the only one that leaves the machine, so it is
 * the tool that most needs the user's confirmation; deleting is the only one that is not reversible, which
 * is why it leaves a tombstone and asks first.
 */
class LinksSearchTool : public AI::AbstractTool {
public:
  explicit LinksSearchTool(std::shared_ptr<LinksStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "search"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<LinksStore> m_store;
};

class LinksSaveTool : public AI::AbstractTool {
public:
  explicit LinksSaveTool(std::shared_ptr<LinksStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "save"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<LinksStore> m_store;
};

class LinksOpenTool : public AI::AbstractTool {
public:
  explicit LinksOpenTool(std::shared_ptr<LinksStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "open"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<LinksStore> m_store;
};

class LinksDeleteTool : public AI::AbstractTool {
public:
  explicit LinksDeleteTool(std::shared_ptr<LinksStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "delete"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<LinksStore> m_store;
};
