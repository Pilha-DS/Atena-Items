#include "links-tools.hpp"
#include <functional>
#include <optional>
#include <utility>
#include <QDateTime>
#include <QDesktopServices>
#include <QUrl>
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
};

struct SearchHit {
  std::string id;
  std::string url;
  std::string title;
  std::string saved;
  std::string opened;
  int openCount = 0;
};

struct SaveInput {
  std::string url;
  std::string title;
};

struct SaveOutput {
  std::string id;
  std::string url;
  std::string title;
  /// False when the page was already in the inbox and the row was refreshed instead.
  bool created = true;
};

struct OpenInput {
  std::string id;
};

struct OpenOutput {
  std::string id;
  std::string url;
  bool opened = true;
};

struct DeleteInput {
  std::string id;
};

struct DeleteOutput {
  std::string id;
  std::string url;
  bool deleted = true;
};

SearchHit hit(const LinksStore::Link &link) {
  return SearchHit{
      .id = link.uuid,
      .url = link.url,
      .title = link.title,
      .saved = isoTime(link.createdTime),
      // Never opened: an empty string reads better than 1970 in the list.
      .opened = link.lastOpenedTime == 0 ? std::string() : isoTime(link.lastOpenedTime),
      .openCount = link.openCount,
  };
}

} // namespace

// ---------------------------------------------------------------------------
// search
// ---------------------------------------------------------------------------

std::string LinksSearchTool::description() const {
  return "List the user's saved links (the inbox in the notes app), most recently used first, optionally "
         "filtering by text in the title or the address. Each result carries the id the other links tools "
         "need. Use it before opening or deleting anything: never delete a link from a guess.";
}

std::string LinksSearchTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"query":{"type":"string","description":"Text to look for in the title or the URL. Leave empty to list the whole inbox."},"limit":{"type":"integer","minimum":1,"maximum":100,"description":"Maximum number of links. Defaults to 20."}},"additionalProperties":false})";
}

std::optional<std::string> LinksSearchTool::invocationSummary(std::string_view arguments) const {
  SearchInput input;
  if (glz::read_json(input, arguments)) return std::nullopt;
  return input.query.empty() ? std::optional<std::string>("recent links") : std::optional(input.query);
}

AI::AbstractTool::RawToolTask LinksSearchTool::runRaw(std::string_view arguments) {
  SearchInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  return blocking([store = m_store, input = std::move(input)]() -> RawToolResult {
    auto links = store->search(input.query, input.limit.value_or(20));
    if (!links) return std::unexpected(links.error());

    std::vector<SearchHit> hits;
    hits.reserve(links->size());
    for (const auto &link : *links)
      hits.push_back(hit(link));

    return serialize(hits);
  });
}

// ---------------------------------------------------------------------------
// save
// ---------------------------------------------------------------------------

std::string LinksSaveTool::description() const {
  return "Save a link in the user's inbox so it is one keyword away later. Pass the address exactly as it "
         "appeared — a bare domain is accepted and gets https:// — and a short title if the page name is "
         "known; leave the title empty when it is not, the address is shown instead. Saving the same page "
         "again does not duplicate it: the existing entry is refreshed and moves up. The link is saved "
         "locally and pushed to the other devices on the next sync.";
}

std::string LinksSaveTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"url":{"type":"string","description":"The http(s) address to keep. A bare domain such as example.com is accepted."},"title":{"type":"string","description":"Short label for the link. May be empty."}},"required":["url"],"additionalProperties":false})";
}

std::optional<std::string> LinksSaveTool::invocationSummary(std::string_view arguments) const {
  SaveInput input;
  if (glz::read_json(input, arguments) || input.url.empty()) return std::nullopt;
  return input.url;
}

AI::AbstractTool::RawToolTask LinksSaveTool::runRaw(std::string_view arguments) {
  SaveInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.url.empty()) return ready(std::unexpected(std::string("The url must not be empty.")));

  return blocking([store = m_store, input = std::move(input)]() -> RawToolResult {
    auto saved = store->save(input.url, input.title);
    if (!saved) return std::unexpected(saved.error());

    return serialize(SaveOutput{
        .id = saved->link.uuid,
        .url = saved->link.url,
        .title = saved->link.title,
        .created = saved->created,
    });
  });
}

// ---------------------------------------------------------------------------
// open
// ---------------------------------------------------------------------------

std::string LinksOpenTool::description() const {
  return "Open a saved link in the user's default browser and count it as opened, which moves it up in the "
         "inbox on every device. Use the id from links_search. This leaves the machine (it launches the "
         "browser), so only do it when the user asked to open the link.";
}

std::string LinksOpenTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Id (uuid) of the link, from links_search."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> LinksOpenTool::invocationSummary(std::string_view arguments) const {
  OpenInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask LinksOpenTool::runRaw(std::string_view arguments) {
  OpenInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The link id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    // The row is marked first: if the browser refuses, the ranking change still tells the user the link was
    // the one they meant, and the error explains what failed.
    auto opened = store->markOpened(id);
    if (!opened) return std::unexpected(opened.error());

    if (!QDesktopServices::openUrl(QUrl(QString::fromStdString(opened->url))))
      return std::unexpected(std::format("Could not open {} in the browser.", opened->url));

    return serialize(OpenOutput{.id = opened->uuid, .url = opened->url});
  });
}

// ---------------------------------------------------------------------------
// delete
// ---------------------------------------------------------------------------

std::string LinksDeleteTool::description() const {
  return "Delete a link from the inbox for good. The app has no trash for links, so this cannot be undone: "
         "confirm the id with links_search first and never delete more than the user asked for. The deletion "
         "reaches the other devices on the next sync.";
}

std::string LinksDeleteTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Id (uuid) of the link to delete."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> LinksDeleteTool::invocationSummary(std::string_view arguments) const {
  DeleteInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask LinksDeleteTool::runRaw(std::string_view arguments) {
  DeleteInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The link id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    auto existing = store->read(id);
    if (!existing) return std::unexpected(existing.error());

    const std::string url = existing->url;
    if (auto removed = store->remove(id); !removed) return std::unexpected(removed.error());

    return serialize(DeleteOutput{.id = id, .url = url});
  });
}
