#include "agenda-tools.hpp"
#include <algorithm>
#include <format>
#include <functional>
#include <optional>
#include <utility>
#include <vector>
#include <QDate>
#include <QDateTime>
#include <QTime>
#include <QTimeZone>
#include <QtConcurrent/QtConcurrentRun>
#include <glaze/json/read.hpp>
#include <glaze/json/write.hpp>

namespace {

using RawToolResult = AI::AbstractTool::RawToolResult;
using RawToolTask = AI::AbstractTool::RawToolTask;

RawToolTask ready(RawToolResult result) { return {QtFuture::makeReadyValueFuture(std::move(result)), {}}; }

/// The database is small and local, but the app may be holding it: never touch the disk on the UI thread.
RawToolTask blocking(std::function<RawToolResult()> work) { return {QtConcurrent::run(std::move(work)), {}}; }

constexpr std::int64_t HOUR_MS = 60LL * 60LL * 1000LL;
constexpr std::int64_t WEEK_DAYS = 7;

/// ISO-8601 with the machine's offset, so the model never has to guess the zone.
std::string isoTime(std::int64_t milliseconds) {
  return QDateTime::fromMSecsSinceEpoch(milliseconds).toString(Qt::ISODate).toStdString();
}

template <typename T> RawToolResult serialize(const T &value) {
  std::string out;
  if (const auto error = glz::write_json(value, out)) return std::unexpected(glz::format_error(error));
  return AI::ToolOutput{.content = std::move(out)};
}

QDateTime local(std::int64_t milliseconds) { return QDateTime::fromMSecsSinceEpoch(milliseconds); }

std::int64_t startOfDay(QDate date) {
  return QDateTime(date, QTime(0, 0), QTimeZone::systemTimeZone()).toMSecsSinceEpoch();
}

std::int64_t startOfDayOf(std::int64_t milliseconds) { return startOfDay(local(milliseconds).date()); }

/// Midnight after the day [milliseconds] falls on, which is how the app ends an all-day event.
std::int64_t nextDayOf(std::int64_t milliseconds) {
  return startOfDay(local(milliseconds).date().addDays(1));
}

std::int64_t addDays(std::int64_t milliseconds, int days) {
  return local(milliseconds).addDays(days).toMSecsSinceEpoch();
}

struct Moment {
  std::int64_t milliseconds = 0;
  /// A bare date ("2026-11-14") means the whole day, which is what makes an event all-day.
  bool dateOnly = false;
};

bool hasExplicitZone(const QString &text) {
  if (text.endsWith('Z', Qt::CaseInsensitive)) return true;
  const auto separator = text.indexOf('T');
  const auto from = separator < 0 ? text.size() : separator;
  return text.indexOf('+', from) >= 0 || text.indexOf('-', from) >= 0;
}

/**
 * ISO-8601 in the user's wall clock: a date, a date and time, or a full timestamp with an offset. A text
 * without an offset is read in the system zone, so "2026-11-14T09:00" is 9 in the morning where the user
 * lives instead of 9 UTC.
 */
std::optional<Moment> parseMoment(std::string_view raw, std::string &error) {
  auto text = QString::fromUtf8(raw.data(), raw.size()).trimmed();
  if (text.isEmpty()) {
    error = "A date is required.";
    return std::nullopt;
  }

  if (!text.contains('T') && !text.contains(' ')) {
    const auto date = QDate::fromString(text, Qt::ISODate);
    if (!date.isValid()) {
      error = std::format("Could not understand the date \"{}\". Use ISO-8601, for example 2026-11-14 or "
                          "2026-11-14T09:30.",
                          raw);
      return std::nullopt;
    }
    return Moment{.milliseconds = startOfDay(date), .dateOnly = true};
  }

  text.replace(' ', 'T');
  QDateTime moment = QDateTime::fromString(text, Qt::ISODate);
  if (!moment.isValid()) moment = QDateTime::fromString(text, Qt::ISODateWithMs);
  if (!moment.isValid()) {
    error = std::format("Could not understand the date \"{}\". Use ISO-8601, for example 2026-11-14 or "
                        "2026-11-14T09:30.",
                        raw);
    return std::nullopt;
  }

  if (!hasExplicitZone(text)) moment = QDateTime(moment.date(), moment.time(), QTimeZone::systemTimeZone());
  return Moment{.milliseconds = moment.toMSecsSinceEpoch(), .dateOnly = false};
}

struct EventOut {
  std::string id;
  std::string title;
  std::string start;
  std::string end;
  bool allDay = false;
  std::string location;
  std::string description;
  std::string updated;
};

EventOut toOut(const AgendaStore::Event &event) {
  return EventOut{
      .id = event.uuid,
      .title = event.title,
      .start = isoTime(event.startTime),
      .end = isoTime(event.endTime),
      .allDay = event.allDay,
      .location = event.location,
      .description = event.description,
      .updated = isoTime(event.updatedTime),
  };
}

struct SearchInput {
  std::optional<std::string> from;
  std::optional<std::string> to;
  std::string query;
  std::optional<int> limit;
};

struct SearchOutput {
  std::string from;
  std::string to;
  int count = 0;
  std::vector<EventOut> events;
};

struct ReadInput {
  std::string id;
};

struct CreateInput {
  std::string title;
  std::string start;
  std::optional<std::string> end;
  std::optional<bool> allDay;
  std::optional<std::string> description;
  std::optional<std::string> location;
};

struct UpdateInput {
  std::string id;
  std::optional<std::string> title;
  std::optional<std::string> description;
  std::optional<std::string> location;
  std::optional<std::string> start;
  std::optional<std::string> end;
  std::optional<bool> allDay;
};

struct DeleteInput {
  std::string id;
};

struct DeleteOutput {
  std::string id;
  bool deleted = true;
};

/// Search hits carry a capped description; the full text is one `agenda_read` away.
constexpr std::size_t PREVIEW_BYTES = 240;

} // namespace

// ---------------------------------------------------------------------------
// search
// ---------------------------------------------------------------------------

std::string AgendaSearchTool::description() const {
  return "List the user's calendar events in a time range, oldest first. Use it to answer \"what do I have "
         "on Thursday\" and to find the ids the other agenda tools need. Dates are ISO-8601: a date "
         "(\"2026-11-14\", meaning the whole day) or a date and time (\"2026-11-14T09:30\"), in the user's "
         "local time. `from` defaults to the beginning of today; `to` is the exclusive end, so pass "
         "\"2026-11-15\" to cover all of tomorrow. Without `to` the range is one day (when `from` is a "
         "date) or seven days. All-day events come with allDay true.";
}

std::string AgendaSearchTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"from":{"type":"string","description":"Start of the range, ISO-8601 date or date-time. Defaults to the beginning of today."},"to":{"type":"string","description":"Exclusive end of the range. A bare date covers that whole day. Defaults to the end of the day after `from`."},"query":{"type":"string","description":"Text to look for in the title, description or location. Leave empty to list every event in the range."},"limit":{"type":"integer","minimum":1,"maximum":200,"description":"Maximum number of events. Defaults to 50."}},"additionalProperties":false})";
}

std::optional<std::string> AgendaSearchTool::invocationSummary(std::string_view arguments) const {
  SearchInput input;
  if (glz::read_json(input, arguments)) return std::nullopt;
  if (!input.query.empty()) return input.query;
  if (input.from && input.to) return std::format("{} → {}", *input.from, *input.to);
  return std::optional<std::string>(input.from.value_or("upcoming events"));
}

AI::AbstractTool::RawToolTask AgendaSearchTool::runRaw(std::string_view arguments) {
  SearchInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  std::string error;
  std::int64_t fromMs = startOfDayOf(QDateTime::currentMSecsSinceEpoch());
  bool fromIsDate = true;
  if (input.from && !input.from->empty()) {
    const auto moment = parseMoment(*input.from, error);
    if (!moment) return ready(std::unexpected(error));
    fromMs = moment->milliseconds;
    fromIsDate = moment->dateOnly;
  }

  std::int64_t toMs = 0;
  if (input.to && !input.to->empty()) {
    const auto moment = parseMoment(*input.to, error);
    if (!moment) return ready(std::unexpected(error));
    toMs = moment->dateOnly ? nextDayOf(moment->milliseconds) : moment->milliseconds;
  } else if (input.from && !fromIsDate) {
    toMs = addDays(fromMs, WEEK_DAYS);
  } else {
    toMs = nextDayOf(fromMs);
  }

  if (toMs <= fromMs)
    return ready(std::unexpected(std::string("The end of the range must be after its start.")));

  AgendaStore::Range range{.from = fromMs, .to = toMs};

  return blocking([store = m_store, range, query = input.query, limit = input.limit]() -> RawToolResult {
    auto events = store->search(range, query, limit.value_or(50));
    if (!events) return std::unexpected(events.error());

    std::vector<EventOut> out;
    out.reserve(events->size());
    for (const auto &event : *events) {
      auto hit = toOut(event);
      const auto preview = AI::truncateUtf8(hit.description, PREVIEW_BYTES);
      hit.description = std::string(preview.text);
      if (preview.truncated) hit.description += "...";
      out.emplace_back(std::move(hit));
    }

    return serialize(SearchOutput{
        .from = isoTime(range.from),
        .to = isoTime(range.to),
        .count = static_cast<int>(out.size()),
        .events = std::move(out),
    });
  });
}

// ---------------------------------------------------------------------------
// read
// ---------------------------------------------------------------------------

std::string AgendaReadTool::description() const {
  return "Read one event in full, by id. Use the id from agenda_search.";
}

std::string AgendaReadTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Event id (uuid) taken from agenda_search."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> AgendaReadTool::invocationSummary(std::string_view arguments) const {
  ReadInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask AgendaReadTool::runRaw(std::string_view arguments) {
  ReadInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The event id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    auto event = store->read(id);
    if (!event) return std::unexpected(event.error());
    return serialize(toOut(*event));
  });
}

// ---------------------------------------------------------------------------
// create
// ---------------------------------------------------------------------------

std::string AgendaCreateTool::description() const {
  return "Create a calendar event. Pass a title and a start; the end defaults to one hour later. Times are "
         "ISO-8601 in the user's local time (\"2026-11-14T09:30\"); a bare date (\"2026-11-14\") makes an "
         "all-day event. For an all-day event spanning several days, `end` is the last day, inclusive. To "
         "move something that already exists, use agenda_update instead of creating a duplicate. The event "
         "is saved locally and pushed to the user's other devices on the next sync.";
}

std::string AgendaCreateTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"title":{"type":"string","description":"Title of the event."},"start":{"type":"string","description":"Start, ISO-8601 date or date-time in local time (\"2026-11-14T09:30\"). A bare date makes the event all-day."},"end":{"type":"string","description":"End, ISO-8601. A date and time for a timed event; for an all-day event, the last day of the event (inclusive). Defaults to one hour after the start, or the rest of the day for an all-day event."},"allDay":{"type":"boolean","description":"Force an all-day event. Omit it when the dates already say what is meant."},"description":{"type":"string","description":"Longer notes for the event."},"location":{"type":"string","description":"Where the event happens. May be a room, an address or a link."}},"required":["title","start"],"additionalProperties":false})";
}

std::optional<std::string> AgendaCreateTool::invocationSummary(std::string_view arguments) const {
  CreateInput input;
  if (glz::read_json(input, arguments) || input.title.empty()) return std::nullopt;
  return input.title;
}

AI::AbstractTool::RawToolTask AgendaCreateTool::runRaw(std::string_view arguments) {
  CreateInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));

  const auto title = QString::fromStdString(input.title).trimmed();
  if (title.isEmpty()) return ready(std::unexpected(std::string("An event needs a title.")));

  std::string error;
  const auto start = parseMoment(input.start, error);
  if (!start) return ready(std::unexpected(error));

  const bool allDay = input.allDay.value_or(start->dateOnly);
  const auto startMs = allDay ? startOfDayOf(start->milliseconds) : start->milliseconds;

  std::int64_t endMs = 0;
  if (input.end && !input.end->empty()) {
    const auto end = parseMoment(*input.end, error);
    if (!end) return ready(std::unexpected(error));
    endMs = allDay ? nextDayOf(end->milliseconds) : end->milliseconds;
  } else {
    endMs = allDay ? nextDayOf(startMs) : startMs + HOUR_MS;
  }

  if (endMs <= startMs)
    return ready(std::unexpected(std::string("The end of the event must be after its start.")));

  AgendaStore::Event event{
      .title = title.toStdString(),
      .description = input.description.value_or(""),
      .location = input.location.value_or(""),
      .startTime = startMs,
      .endTime = endMs,
      .allDay = allDay,
  };

  return blocking([store = m_store, event = std::move(event)]() -> RawToolResult {
    auto id = store->create(event);
    if (!id) return std::unexpected(id.error());

    auto saved = store->read(*id);
    if (!saved) return std::unexpected(saved.error());
    return serialize(toOut(*saved));
  });
}

// ---------------------------------------------------------------------------
// update
// ---------------------------------------------------------------------------

std::string AgendaUpdateTool::description() const {
  return "Change an existing event: title, description, location, start, end or allDay, in any "
         "combination. Fields you leave out keep their current value. Use the id from agenda_search and "
         "read it first when you are not sure what the event says. To move an event to another day, pass "
         "the new start (and end); moving or resizing keeps the event consistent, so an all-day event "
         "stays all-day.";
}

std::string AgendaUpdateTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Event id (uuid) taken from agenda_search."},"title":{"type":"string","description":"New title."},"description":{"type":"string","description":"New notes for the event. Send an empty string to clear them."},"location":{"type":"string","description":"New location. Send an empty string to clear it."},"start":{"type":"string","description":"New start, ISO-8601 date or date-time."},"end":{"type":"string","description":"New end, ISO-8601. For an all-day event this is the last day, inclusive."},"allDay":{"type":"boolean","description":"Turn the event into an all-day event, or back into a timed one."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> AgendaUpdateTool::invocationSummary(std::string_view arguments) const {
  UpdateInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.title ? std::optional(*input.title) : std::optional(input.id);
}

AI::AbstractTool::RawToolTask AgendaUpdateTool::runRaw(std::string_view arguments) {
  UpdateInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The event id must not be empty.")));

  std::string error;
  std::optional<Moment> start;
  std::optional<Moment> end;
  if (input.start && !input.start->empty()) {
    start = parseMoment(*input.start, error);
    if (!start) return ready(std::unexpected(error));
  }
  if (input.end && !input.end->empty()) {
    end = parseMoment(*input.end, error);
    if (!end) return ready(std::unexpected(error));
  }

  if (!input.title && !input.description && !input.location && !start && !end && !input.allDay)
    return ready(std::unexpected(
        std::string("Nothing to change: pass at least one of title, description, location, start, end or "
                    "allDay.")));

  AgendaStore::EventPatch patch;
  if (input.title) patch.title = QString::fromStdString(*input.title).trimmed().toStdString();
  if (input.description) patch.description = *input.description;
  if (input.location) patch.location = *input.location;

  const auto id = std::move(input.id);
  const auto allDay = input.allDay;

  return blocking([store = m_store, id, patch, start, end, allDay]() mutable -> RawToolResult {
    const auto current = store->read(id);
    if (!current) return std::unexpected(current.error());

    const bool dayLong = allDay.value_or(current->allDay);
    const bool timesTouched = start || end || allDay.has_value();

    if (timesTouched) {
      auto startMs = start ? start->milliseconds : current->startTime;
      auto endMs = end ? end->milliseconds : current->endTime;

      if (dayLong) {
        // An all-day event lives on day boundaries: the end is midnight after the last day.
        const auto originalDuration = std::max<std::int64_t>(current->endTime - current->startTime, 1);
        startMs = startOfDayOf(startMs);
        if (end) {
          endMs = nextDayOf(end->milliseconds);
        } else if (start) {
          // Moved to another day: keep how long it lasts, rounded up to whole days.
          endMs = nextDayOf(startMs + std::max<std::int64_t>(originalDuration - 1, 0));
        } else {
          // Only the flag changed: snap the existing end to the following midnight.
          endMs = nextDayOf(std::max(endMs - 1, startMs));
        }
      } else if (start && !end && endMs <= startMs) {
        endMs = startMs + HOUR_MS;
      }

      if (endMs <= startMs)
        return std::unexpected(std::string("The end of the event must be after its start."));

      patch.startTime = startMs;
      patch.endTime = endMs;
      patch.allDay = dayLong;
    }

    auto updated = store->update(id, patch);
    if (!updated) return std::unexpected(updated.error());
    return serialize(toOut(*updated));
  });
}

// ---------------------------------------------------------------------------
// delete
// ---------------------------------------------------------------------------

std::string AgendaDeleteTool::description() const {
  return "Delete an event, everywhere: it is removed from the app and the deletion is propagated to the "
         "hub on the next sync, so it disappears from the phone too. There is no undo, and this is the one "
         "agenda action without a way back — confirm the id with agenda_search (or agenda_read) before "
         "deleting, and never delete from a guess.";
}

std::string AgendaDeleteTool::generateInputSchema() const {
  return R"({"type":"object","properties":{"id":{"type":"string","description":"Id (uuid) of the event to delete."}},"required":["id"],"additionalProperties":false})";
}

std::optional<std::string> AgendaDeleteTool::invocationSummary(std::string_view arguments) const {
  DeleteInput input;
  if (glz::read_json(input, arguments) || input.id.empty()) return std::nullopt;
  return input.id;
}

AI::AbstractTool::RawToolTask AgendaDeleteTool::runRaw(std::string_view arguments) {
  DeleteInput input;
  if (const auto error = glz::read_json(input, arguments))
    return ready(std::unexpected(glz::format_error(error, arguments)));
  if (input.id.empty()) return ready(std::unexpected(std::string("The event id must not be empty.")));

  return blocking([store = m_store, id = std::move(input.id)]() -> RawToolResult {
    if (auto removed = store->remove(id); !removed) return std::unexpected(removed.error());
    return serialize(DeleteOutput{.id = id});
  });
}
