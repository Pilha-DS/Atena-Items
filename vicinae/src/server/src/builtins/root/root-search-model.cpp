#include "builtins/root/root-search-model.hpp"
#include "builtins/iamod/iamod-root-section.hpp"
#include "config/config.hpp"
#include "navigation-controller.hpp"
#include "services/calculator-service/abstract-calculator-backend.hpp"
#include "service-registry.hpp"
#include "services/app-service/app-service.hpp"
#include "services/calculator-service/calculator-service.hpp"
#include "services/files-service/file-service.hpp"
#include "services/news/news-service.hpp"
#include "theme/theme.hpp"
#include <chrono>
#include <filesystem>
#include <utility>

constexpr auto CALCULATOR_MIN_CHARS = 3;
// single letters are drive letters, not schemes
constexpr auto MIN_SCHEME_LENGTH = 2;
// How long the query has to stay unchanged before the keyword levels may hit the disk.
constexpr std::chrono::milliseconds CONTENT_SEARCH_DELAY{400};
// The index either answers quickly or it is not going to answer in time to be useful.
constexpr std::chrono::milliseconds FILE_SEARCH_TIMEOUT{5000};

namespace {

bool isAbsolutePathLike(const QString &text) {
  if (text.startsWith('/')) return true;
  return text.size() >= 3 && text[0].isLetter() && text[1] == ':' && (text[2] == '\\' || text[2] == '/');
}

} // namespace

RootSearchModel::RootSearchModel(const ViewScope &scope, QObject *parent)
    : SectionListModel(parent), m_manager(scope.services()->rootItemManager()),
      m_appDb(scope.services()->appDb()), m_newsService(scope.services()->newsService()),
      m_updateService(scope.services()->updateService()), m_calculator(scope.services()->calculatorService()),
      m_fileService(scope.services()->fileService()), m_config(scope.services()->config()),
      m_fileSearchEnabled(m_config->value().searchFilesInRoot) {

  setScope(scope);

  using namespace std::chrono_literals;

  m_fileSearchDebounce.setInterval(50ms);
  m_fileSearchDebounce.setSingleShot(true);

  // Longer than the file debounce: this one guards the expensive levels (disk walks and
  // subprocesses), so it should only fire once the user actually stopped typing.
  m_contentDebounce.setInterval(CONTENT_SEARCH_DELAY);
  m_contentDebounce.setSingleShot(true);

  // Hard caps: the index gets FILE_SEARCH_TIMEOUT to answer, and once the keyword levels start they
  // share a single KEYWORDS_BUDGET no matter how many files are on the disk.
  m_fileSearchDeadline.setInterval(FILE_SEARCH_TIMEOUT);
  m_fileSearchDeadline.setSingleShot(true);

  connect(&m_fileSearchDebounce, &QTimer::timeout, this, &RootSearchModel::startFileSearch);
  connect(&m_fileWatcher, &FileSearchWatcher::finished, this, &RootSearchModel::handleFileSearchFinished);
  connect(&m_fileSearchDeadline, &QTimer::timeout, this, &RootSearchModel::handleFileSearchTimeout);
  connect(&m_contentDebounce, &QTimer::timeout, this,
          [this]() { startContentSearch(std::exchange(m_pendingContentStage, ContentStage::Idle)); });

  connect(m_config, &config::Manager::configChanged, this,
          [this](const config::ConfigValue &next, const config::ConfigValue &prev) {
            m_fileSearchEnabled = next.searchFilesInRoot;
            if (next.updates.showNotice != prev.updates.showNotice) refresh();
          });

  connect(m_manager, &RootItemManager::metadataChanged, this, &RootSearchModel::refresh);
  connect(m_manager, &RootItemManager::itemsChanged, this, &RootSearchModel::refresh);
  connect(m_newsService, &NewsService::itemsChanged, this, &RootSearchModel::refresh);
  connect(m_updateService, &UpdateService::updateChanged, this, &RootSearchModel::refresh);

  connect(&ThemeService::instance(), &ThemeService::themeChanged, this, [this]() {
    if (rowCount() > 0) emit dataChanged(index(0), index(rowCount() - 1), {IconSource, AccessoryColor});
  });

  m_updateSource = new RootUpdateSection(m_updateService);
  m_linkSource = new RootLinkSection;
  m_calcSource = new RootCalculatorSection;
  m_iamodSource = new IamodRootSection;
  m_newsSource = new RootNewsSection;
  m_favoritesSource = new RootFavoritesSection(m_manager);
  m_resultsSource = new RootResultsSection(m_manager);
  m_filesSource = new RootFilesSection(m_appDb);
  m_contentSource = new RootContentSection(RootContentSection::Kind::Documents);
  m_mediaSource = new RootContentSection(RootContentSection::Kind::Media);
  m_fallbackSource = new RootFallbackSection(m_manager);
  m_contentSearch = new ContentSearch(this);

  // IAMod comes first so that asking a question is always one row away, whatever is being searched.
  addSource(m_iamodSource);
  addSource(m_updateSource);
  addSource(m_linkSource);
  addSource(m_calcSource);
  addSource(m_newsSource);
  addSource(m_favoritesSource);
  addSource(m_resultsSource);
  addSource(m_filesSource);
  addSource(m_contentSource);
  addSource(m_mediaSource);
  addSource(m_fallbackSource);

  if (auto *nav = scope.appContext() ? scope.appContext()->navigation.get() : nullptr) {
    connect(nav, &NavigationController::windowVisiblityChanged, this, [this](bool visible) {
      // Searching must die with the window: file and content searches can be expensive and their
      // results are meaningless once the launcher is gone.
      if (!visible) {
        stopSearch();
      } else if (!m_query.empty()) {
        rerunSearch();
      }
    });
  }

  setFilter({});
}

RootSearchModel::~RootSearchModel() {
  m_fileSearchDebounce.stop();
  m_fileWatcher.cancel();
  m_contentSearch->cancel();
}

void RootSearchModel::setFilter(const QString &text) {
  auto query = text.toStdString();
  if (query == m_query) return;
  m_query = std::move(query);
  setSelectFirstOnReset(true);
  scope().clearActions();

  m_calcSource->setResult({});
  m_filesSource->setFiles({});
  m_hasFileResults = false;
  m_fileSearchDebounce.stop();
  m_fileSearchDeadline.stop();
  m_fileWatcher.cancel();
  m_contentDebounce.stop();
  m_pendingContentStage = ContentStage::Idle;
  m_contentSearch->cancel();
  m_contentStage = ContentStage::Idle;
  m_contentSource->setMatches({});
  m_mediaSource->setMatches({});

  rerunSearch();
}

void RootSearchModel::refresh() {
  auto saved = selectFirstOnReset();
  setSelectFirstOnReset(false);
  rerunSearch();
  setSelectFirstOnReset(saved);
  refreshActionPanel();
}

bool RootSearchModel::rerunSearch() {
  auto text = QString::fromStdString(m_query);

  m_iamodSource->setQuery(m_query);

  if (isAbsolutePathLike(text)) {
    std::error_code ec;
    if (std::filesystem::exists(m_query, ec)) {
      // A path query is about finding the file itself, asking the AI has nothing to add.
      m_iamodSource->setVisible(false);
      m_updateSource->setUpdate({});
      m_linkSource->setLink({});
      m_resultsSource->setItems({});
      m_resultsSource->setQueryEmpty(false);
      m_filesSource->setFiles({{std::filesystem::path(m_query), 1.0}});
      m_newsSource->setItems({});
      m_favoritesSource->setItems({});
      m_fallbackSource->setItems({});
      scope().setLoading(false);
      rebuild();
      return true;
    }
  }

  // a link is shown on top of the regular results, it does not replace them
  std::optional<LinkItem> link;

  m_iamodSource->setVisible(true);

  if (auto url = QUrl(text); url.isValid() && url.scheme().size() >= MIN_SCHEME_LENGTH) {
    if (auto app = m_appDb->findDefaultOpener(text)) { link = LinkItem{.app = app, .url = text}; }
  }

  m_linkSource->setLink(std::move(link));
  m_resultsSource->setQueryEmpty(m_query.empty());
  m_fallbackSource->setQuery(m_query);

  std::vector<RootItemManager::ScoredItem> scored;
  if (m_query.empty()) {
    m_manager->search("", scored, {.includeFavorites = false, .prioritizeAliased = false});
    m_updateSource->setUpdate(m_config->value().updates.showNotice ? m_updateService->available()
                                                                   : std::nullopt);
    m_newsSource->setItems(m_newsService->activeItems());
    m_favoritesSource->setItems(m_manager->queryFavorites());
    m_fallbackSource->setItems({});
  } else {
    m_manager->search(text, scored);
    m_updateSource->setUpdate({});
    m_newsSource->setItems({});
    m_favoritesSource->setItems({});

    bool const awaitingFiles = fileSearchApplicable() && !m_hasFileResults;
    m_fallbackSource->setItems(awaitingFiles ? std::vector<std::shared_ptr<RootItem>>{}
                                             : m_manager->fallbackItems());
  }

  std::vector<OwnedResult> results;
  results.reserve(scored.size());
  for (const auto &s : scored) {
    results.push_back({
        .item = s.item.get(),
        .meta = s.meta ? *s.meta : RootItemMetadata{},
    });
  }

  m_resultsSource->setItems(std::move(results));

  refreshCalculator();
  scope().setLoading(fileSearchApplicable());
  if (!text.isEmpty()) m_fileSearchDebounce.start();

  rebuild();
  return false;
}

void RootSearchModel::refreshCalculator() {
  m_calcSource->setResult({});

  if (m_query.empty()) return;

  if (m_query.starts_with('=')) {
    if (auto res = m_calculator->backend()->compute(QString::fromStdString(m_query.substr(1)), {})) {
      m_calcSource->setResult(res.value());
    }
    return;
  }

  if (m_resultsSource->count() > 0 || m_query.size() < CALCULATOR_MIN_CHARS) return;

  if (auto res = m_calculator->backend()->compute(QString::fromStdString(m_query), {})) {
    m_calcSource->setResult(res.value());
  }
}

void RootSearchModel::setSelectedIndex(int index) {
  QString oldId = m_lastCompleterItemId;
  SectionListModel::setSelectedIndex(index);

  int sourceIdx = -1;
  int itemIdx = -1;
  if (!dataItemAt(index, sourceIdx, itemIdx)) {
    if (!oldId.isEmpty()) scope().destroyCurrentCompletion();
    m_lastCompleterItemId.clear();

    return;
  }

  auto *src = sources()[sourceIdx];
  auto id = src->itemId(itemIdx);
  bool const sameItem = (!id.isEmpty() && id == oldId);
  m_lastCompleterItemId = id;

  if (!sameItem) {
    bool createdCompleter = false;

    auto tryCreateCompleter = [&](const RootItem *item) {
      if (!item) return;
      auto args = item->arguments();
      if (args.empty()) return;
      scope().createCompletion(args, item->iconUrl());
      createdCompleter = true;
    };

    if (src == m_resultsSource) {
      tryCreateCompleter(m_resultsSource->rootItem(itemIdx));
    } else if (src == m_favoritesSource) {
      tryCreateCompleter(m_favoritesSource->rootItem(itemIdx));
    }

    if (!createdCompleter) scope().destroyCurrentCompletion();
  }
}

const RootItem *RootSearchModel::selectedRootItem() const {
  int sourceIdx = -1;
  int itemIdx = -1;
  if (!dataItemAt(selectedIndex(), sourceIdx, itemIdx)) return nullptr;

  auto *section = dynamic_cast<const RootItemSection *>(sources()[sourceIdx]);
  return section ? section->rootItem(itemIdx) : nullptr;
}

bool RootSearchModel::fileSearchApplicable() const {
  return m_fileSearchEnabled && m_query.size() >= MIN_FS_TEXT_LENGTH;
}

void RootSearchModel::startFileSearch() {
  if (!fileSearchApplicable()) return;
  if (m_fileWatcher.isRunning()) { m_fileWatcher.cancel(); }
  m_fileSearchQuery = m_query;
  m_fileSearchDeadline.start();
  m_fileWatcher.setFuture(m_fileService->queryAsync(m_query));
}

void RootSearchModel::handleFileSearchFinished() {
  if (!m_fileWatcher.isFinished() || m_fileSearchQuery != m_query) return;
  m_fileSearchDeadline.stop();
  finishFileSearch(m_fileWatcher.result());
}

void RootSearchModel::handleFileSearchTimeout() {
  if (m_fileSearchQuery.empty()) return;

  // The index did not answer in time: give up on it and let the keyword levels take over with the
  // time that is left, instead of holding the whole search hostage.
  m_fileWatcher.cancel();
  m_fileSearchQuery.clear();
  finishFileSearch({});
}

void RootSearchModel::finishFileSearch(std::vector<IndexerFileResult> files) {
  scope().setLoading(false);
  bool const foundFiles = !files.empty();
  m_filesSource->setFiles(std::move(files));
  m_fileSearchQuery.clear();
  m_hasFileResults = true;
  m_fallbackSource->setItems(m_manager->fallbackItems());

  bool const atDefault = selectedIndex() < 0 || selectedIndex() == initialSelectionIndex();

  auto saved = selectFirstOnReset();
  setSelectFirstOnReset(false);
  rebuild();
  setSelectFirstOnReset(saved);

  if (atDefault) {
    setSelectedIndex(initialSelectionIndex());
  } else {
    refreshActionPanel();
  }

  // Keyword search is the last resort: when the index already returned files those rows are the
  // answer, and converting every PDF and image on the disk would burn seconds of CPU for nothing.
  if (!foundFiles) scheduleContentSearch(ContentStage::Documents);
}

void RootSearchModel::scheduleContentSearch(ContentStage stage) {
  if (stage == ContentStage::Idle || !fileSearchApplicable()) return;

  // Wait for a quiet moment before touching the disk: typing bursts used to restart a full home
  // scan on every keystroke, throwing the work away a few milliseconds later.
  m_pendingContentStage = stage;
  m_contentDebounce.start();
}

void RootSearchModel::startContentSearch(ContentStage stage) {
  if (stage == ContentStage::Idle || !fileSearchApplicable()) return;

  // The content search is heavy on purpose: it only ever starts once the index-based file search
  // is done, and each level only starts after the previous one returned everything it had.
  if (m_contentStage != ContentStage::Idle || m_contentSearch->isRunning()) return;

  // The first level opens the shared budget; every later level only gets what is left of it, so a
  // slow disk can never stretch the keyword phase past KEYWORDS_BUDGET.
  auto const now = std::chrono::steady_clock::now();
  if (stage == ContentStage::Documents) m_keywordsDeadline = now + content_search::KEYWORDS_BUDGET;

  auto const remaining = std::chrono::duration_cast<std::chrono::milliseconds>(m_keywordsDeadline - now);
  if (remaining <= std::chrono::milliseconds::zero()) return;

  auto const searchScope =
      stage == ContentStage::Documents ? ContentSearch::Scope::Documents : ContentSearch::Scope::Media;
  m_contentStage = stage;

  auto query = m_query;

  m_contentSearch->search(searchScope, query, remaining,
                          [this, stage, query](std::vector<content_search::Match> matches, bool finished) {
                            if (query != m_query) return;
                            showContentMatches(stage, std::move(matches), finished);
                          });

  if (m_contentSearch->isRunning()) scope().setLoading(true);
}

void RootSearchModel::showContentMatches(ContentStage stage, std::vector<content_search::Match> matches,
                                         bool finished) {
  if (m_contentStage != stage) return;

  auto *section = stage == ContentStage::Documents ? m_contentSource : m_mediaSource;
  section->setMatches(std::move(matches));

  bool const atDefault = selectedIndex() < 0 || selectedIndex() == initialSelectionIndex();

  auto saved = selectFirstOnReset();
  setSelectFirstOnReset(false);
  rebuild();
  setSelectFirstOnReset(saved);

  if (atDefault) {
    setSelectedIndex(initialSelectionIndex());
  } else {
    refreshActionPanel();
  }

  if (!finished) return;

  m_contentStage = ContentStage::Idle;

  if (stage == ContentStage::Documents) {
    startContentSearch(ContentStage::Media);
    if (m_contentStage != ContentStage::Idle) return;
  }

  scope().setLoading(false);
}

void RootSearchModel::stopSearch() {
  m_fileSearchDebounce.stop();
  m_fileSearchDeadline.stop();
  m_fileWatcher.cancel();
  m_contentDebounce.stop();
  m_pendingContentStage = ContentStage::Idle;
  m_contentSearch->cancel();
  m_contentStage = ContentStage::Idle;
  scope().setLoading(false);
}
