#include "notes-tools.hpp"
#include <functional>
#include <optional>
#include <utility>
#include <QDateTime>
#include <QtConcurrent/QtConcurrentRun>
#include <glaze/json/read.hpp>
#include <glaze/json/write.hpp>

namespace {

using RawToolResult = AI::AbstractTool::RawToolResult;
using RawToolTask = AI::AbstractTool::RawToolTask;

RawToolTask ready(RawToolResult result) { return {QtFuture::makeReadyValueFuture(std::move(result)), {}}; }

/// The database is small and local, but it may be locked by the app: never do it on the UI thread.
RawToolTask blocking(std::function<RawToolResult()> work) { return {QtConcurrent::run(std::move(work)), {}}; }

std::string isoTime(std::int64_t milliseconds) {
  return QDateTime::fromMSecsSinceEpoch(milliseconds).toString(Qt::ISODate).toStdString();
}

template <typename T> RawToolResult serialize(const T &value) {
  std::string out;
  if (const auto error = glz::write_json(value, out)) return std::unexpected(glz::format_error(error));
  return AI::ToolOutput{.content = std::move(out)};
}

struct SearchInput {
  std::string query;
  std::optional<int> limit;
  std::optional<bool> includeTrashed;
};

struct SearchHit {
  std::string id;
  std::string title;
  std::string category;
  std::string updated;
  std::string preview;
};

struct ReadInput {
  std::string id;
};

struct ReadOutput {
  std::string id;
  std::string title;
  std::string content;
  std::string category;
  std::string updated;
  bool general = false;
  bool trashed = false;
};

struct CreateInput {
  std::string title;
  std::string content;
  std::string category;
};

struct CreateOutput {
  std::string id;
};

struct UpdateInput {
  std::string id;
  std::optional<std::string> title;
  std::optional<std::string> content;
  std::optional<std::string> category;
  bool append = false;
};

struct DeleteInput {
  std::string id;
};

struct DeleteOutput {
  std::string id;
  bool trashed = true;
};

void fill(ReadOutput &out, const NotesStore::Note &note) {
  out.id = note.uuid;
  out.title = note.title;
  out.content = note.content;
  out.category = note.category;
  out.updated = isoTime(note.updatedTime);
  out.general = note.isScratchpad;
  out.trashed = note.isTrashed;
}

constexpr auto GENERAL_NOTE_ID = "geral";

} // namespace

// ---------------------------------------------------------------------------
// search
// ---------------------------------------------------------------------------

std::string NotesSearchTool::description() const {
  return "List the user's notes, newest first, optionally filtering by text. Use it to see what exists "
         "before creating, editing or deleting anything, and to find the ids the other notes tools need. "
         "Each result carries a short preview only; call notes_read for the full text. Encrypted notes are "
         "never listed here and cannot be read or changed. The general note (the scratchpad window) is not "
         "listed either: use the id \"geral\" with notes_read to read it.";
}

std::string NotesSearchTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"query":{"type":"string","description":"Text to look for in the title or body. Leave empty to simply list the most recent notes."},"limit":{"type":"integer","minimum":1,"maximum":100,"description":"Maximum number of notes. Defaults to 20."},"includeTrashed":{"type":"boolean","description":"Also list notes that are in the trash. Defaults to false."}},"additionalProperties":false})";
}

std::optional<std::string> NotesSearchTool::invocationSummary(std::string_view arguments) const {
  SearchInput input;
  if (glz::read_json(input, arguments)) return std::nullopt;
  return input.query.empty() ? std::optional<std::string>("recent notes") : std::optional(input.query);
}

AI::AbstractTool::RawToolTask NotesSearchTool::runRaw(std::string_view arguments) {
  SearchInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  return blocking([store = m_store, input = std::move(input)]() -> RawToolResult {
    auto notes = store->search(input.query, input.limit.value_or(20), input.includeTrashed.value_or(false));
    if (!notes) return std::unexpected(notes.error());

    std::vector<SearchHit> hits;
    hits.reserve(notes->size());
    for (const auto &note : *notes) {
      hits.push_back(SearchHit{
          .id = note.uuid,
          .title = note.title,
          .category = note.category,
          .updated = isoTime(note.updatedTime),
          .preview = note.content,
      });
    }

    return serialize(hits);
  });
}

// ---------------------------------------------------------------------------
// read
// ---------------------------------------------------------------------------

std::string NotesReadTool::description() const {
  return "Read one note in full, by id. Use the id from notes_search, or the special id \"geral\" for the "
         "user's general note (the scratchpad). Encrypted notes are refused: their text only exists with "
         "the user's password, so do not pretend to know it.";
}

std::string NotesReadTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Note id (uuid), or \"geral\" for the general note."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> NotesReadTool::invocationSummary(std::string_view arguments) const {
  ReadInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask NotesReadTool::runRaw(std::string_view arguments) {
  ReadInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The note id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    auto note = store->read(id);
    if (!note) return std::unexpected(note.error());

    ReadOutput out;
    fill(out, *note);
    return serialize(out);
  });
}

// ---------------------------------------------------------------------------
// create
// ---------------------------------------------------------------------------

std::string NotesCreateTool::description() const {
  return "Create a note. Pass a title (it may be empty, but a short one helps the user find it later), the "
         "content, and optionally a category that already exists. The note is saved locally and pushed to "
         "the other devices on the next sync. To split the general note into several notes, read it with "
         "notes_read (id \"geral\") and create one note per part, keeping the user's wording unless they "
         "asked you to rewrite it.";
}

std::string NotesCreateTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"title":{"type":"string","description":"Title of the note. May be empty."},"content":{"type":"string","description":"Full text of the note."},"category":{"type":"string","description":"Existing category name. Leave empty for no category."}},"required":["content"],"additionalProperties":false})";
}

std::optional<std::string> NotesCreateTool::invocationSummary(std::string_view arguments) const {
  CreateInput input;
  if (glz::read_json(input, arguments)) return std::nullopt;
  return input.title.empty() ? std::optional<std::string>("new note") : std::optional(input.title);
}

AI::AbstractTool::RawToolTask NotesCreateTool::runRaw(std::string_view arguments) {
  CreateInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.content.empty() && input.title.empty())
    return ready(std::unexpected(std::string("A note needs a title or some content.")));

  return blocking([store = m_store, input = std::move(input)]() -> RawToolResult {
    auto id = store->create(input.title, input.content, input.category);
    if (!id) return std::unexpected(id.error());
    return serialize(CreateOutput{.id = *id});
  });
}

// ---------------------------------------------------------------------------
// update
// ---------------------------------------------------------------------------

std::string NotesUpdateTool::description() const {
  return "Change an existing note: title, content, category or any combination. By default the content is "
         "replaced; set append to true to add text at the end instead. Use the id from notes_search, or "
         "\"geral\" to edit the general note (the scratchpad) — passing an empty content clears it. "
         "Encrypted notes are refused. Only change what the user asked for; keep the rest of the text "
         "intact.";
}

std::string NotesUpdateTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Note id (uuid), or \"geral\" for the general note."},"title":{"type":"string","description":"New title."},"content":{"type":"string","description":"New text of the note, or the text to append when append is true."},"category":{"type":"string","description":"New category name. Send an empty string to remove the category."},"append":{"type":"boolean","description":"Append content to the end instead of replacing it. Defaults to false."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> NotesUpdateTool::invocationSummary(std::string_view arguments) const {
  UpdateInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id == GENERAL_NOTE_ID ? std::optional<std::string>("general note") : std::optional(input.id);
}

AI::AbstractTool::RawToolTask NotesUpdateTool::runRaw(std::string_view arguments) {
  UpdateInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The note id must not be empty.")));

  return blocking([store = m_store, input = std::move(input)]() -> RawToolResult {
    auto updated = store->update(input.id, input.title, input.content, input.category, input.append);
    if (!updated) return std::unexpected(updated.error());

    ReadOutput out;
    if (auto note = store->read(input.id)) fill(out, *note);
    return serialize(out);
  });
}

// ---------------------------------------------------------------------------
// delete
// ---------------------------------------------------------------------------

std::string NotesDeleteTool::description() const {
  return "Move a note to the trash. This is reversible: the user can restore it in the app. The general "
         "note cannot be deleted (clear its content with notes_update instead), and encrypted notes are "
         "refused. Confirm the id with notes_read before deleting; never delete several notes from a guess.";
}

std::string NotesDeleteTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Id (uuid) of the note to move to the trash."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> NotesDeleteTool::invocationSummary(std::string_view arguments) const {
  DeleteInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask NotesDeleteTool::runRaw(std::string_view arguments) {
  DeleteInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The note id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    if (auto trashed = store->trash(id); !trashed) return std::unexpected(trashed.error());
    return serialize(DeleteOutput{.id = id});
  });
}
