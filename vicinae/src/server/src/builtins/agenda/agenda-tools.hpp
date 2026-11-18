#pragma once

#include <memory>
#include <string>
#include "agenda-store.hpp"
#include "services/ai/ai-tool.hpp"

/**
 * Event tools for the AI chat: list, read, create, edit and delete.
 *
 * The store is shared instead of owned because the database path comes from the extension preferences,
 * which arrive after the tools are registered. The tool names are the ones the model sees
 * (`agenda_create`, …); the descriptions are what teach it the date format and the all-day rules.
 */
class AgendaSearchTool : public AI::AbstractTool {
public:
  explicit AgendaSearchTool(std::shared_ptr<AgendaStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "search"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<AgendaStore> m_store;
};

class AgendaReadTool : public AI::AbstractTool {
public:
  explicit AgendaReadTool(std::shared_ptr<AgendaStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "read"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<AgendaStore> m_store;
};

class AgendaCreateTool : public AI::AbstractTool {
public:
  explicit AgendaCreateTool(std::shared_ptr<AgendaStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "create"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<AgendaStore> m_store;
};

class AgendaUpdateTool : public AI::AbstractTool {
public:
  explicit AgendaUpdateTool(std::shared_ptr<AgendaStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "update"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<AgendaStore> m_store;
};

class AgendaDeleteTool : public AI::AbstractTool {
public:
  explicit AgendaDeleteTool(std::shared_ptr<AgendaStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "delete"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<AgendaStore> m_store;
};
