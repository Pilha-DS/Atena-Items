#pragma once

#include <memory>
#include <string>
#include "notes-store.hpp"
#include "services/ai/ai-tool.hpp"

/**
 * Note tools for the AI chat: list, read, create, edit and send to the trash.
 *
 * The store is shared instead of owned because the database path comes from the extension preferences,
 * which arrive after the tools are registered. Encrypted notes never pass through: the store refuses them
 * and the search never lists them, so the model cannot even suggest editing one.
 */
class NotesSearchTool : public AI::AbstractTool {
public:
  explicit NotesSearchTool(std::shared_ptr<NotesStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "search"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<NotesStore> m_store;
};

class NotesReadTool : public AI::AbstractTool {
public:
  explicit NotesReadTool(std::shared_ptr<NotesStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "read"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<NotesStore> m_store;
};

class NotesCreateTool : public AI::AbstractTool {
public:
  explicit NotesCreateTool(std::shared_ptr<NotesStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "create"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<NotesStore> m_store;
};

class NotesUpdateTool : public AI::AbstractTool {
public:
  explicit NotesUpdateTool(std::shared_ptr<NotesStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "update"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<NotesStore> m_store;
};

class NotesDeleteTool : public AI::AbstractTool {
public:
  explicit NotesDeleteTool(std::shared_ptr<NotesStore> store) : m_store(std::move(store)) {}

  std::string name() const override { return "delete"; }
  std::string description() const override;
  std::string generateInputSchema() const override;
  std::optional<std::string> invocationSummary(std::string_view arguments) const override;
  RawToolTask runRaw(std::string_view arguments) override;

private:
  std::shared_ptr<NotesStore> m_store;
};
