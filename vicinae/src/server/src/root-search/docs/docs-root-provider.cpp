#include "root-search/docs/docs-root-provider.hpp"

#include "actions/root-search-actions.hpp"
#include "service-registry.hpp"
#include "services/toast/toast-service.hpp"
#include "theme/colors.hpp"
#include "ui/action-panel/action-panel-state.hpp"
#include "ui/image/url.hpp"

#include <QDesktopServices>
#include <QFile>
#include <QFileInfo>
#include <QUrl>

#include <utility>

namespace {

class OpenDocsFileAction : public AbstractAction {
  Q_DECLARE_TR_FUNCTIONS(OpenDocsFileAction)

public:
  explicit OpenDocsFileAction(std::filesystem::path path)
      : AbstractAction(tr("Open"), ImageURL::builtin(BuiltinIcon::BlankDocument)), m_path(std::move(path)) {
    setPrimary(true);
  }

  void execute(ApplicationContext *ctx) override {
    if (m_path.empty() || !QDesktopServices::openUrl(QUrl::fromLocalFile(QString::fromStdString(m_path.string())))) {
      ctx->services->toastService()->failure(tr("Could not open the file"));
      return;
    }
    ctx->navigation->closeWindow({.clearRootSearch = true});
  }

private:
  std::filesystem::path m_path;
};

QString joinTags(const std::vector<std::string> &tags) {
  QStringList parts;
  parts.reserve(static_cast<int>(tags.size()));
  for (const auto &tag : tags)
    parts << QString::fromStdString(tag);
  return parts.join(QStringLiteral(", "));
}

} // namespace

DocsRootItem::DocsRootItem(DocsStore::Document doc, std::filesystem::path absolutePath)
    : m_doc(std::move(doc)), m_absolutePath(std::move(absolutePath)),
      m_icon(m_absolutePath.empty() ? ImageURL::builtin(BuiltinIcon::BlankDocument)
                                    : ImageURL::fileIcon(m_absolutePath)) {}

EntrypointId DocsRootItem::uniqueId() const { return EntrypointId{"docs", m_doc.uuid}; }

QString DocsRootItem::title() const { return QString::fromStdString(m_doc.name); }

QString DocsRootItem::subtitle() const {
  QStringList bits;
  // Surface the primary subnome so a match on "nfe" is obvious in the list.
  if (!m_doc.aliases.empty()) bits << QString::fromStdString(m_doc.aliases.front());
  if (!m_doc.folder.empty()) bits << QString::fromStdString(m_doc.folder);
  else if (!m_doc.tags.empty()) bits << joinTags(m_doc.tags);
  if (bits.isEmpty()) return tr("Docs");
  return bits.join(QStringLiteral(" · "));
}

std::optional<QString> DocsRootItem::unlocalizedTitle() const {
  // Root search scores keywords at 0.6 but title/unlocalizedTitle at 1.0 — the
  // same weight as a launcher alias. Tag paths and subnomes belong there so
  // typing "nfe" ranks like an alias, not a weak keyword hit.
  QStringList parts;
  parts.reserve(static_cast<int>(m_doc.aliases.size() + m_doc.tags.size()));
  for (const auto &alias : m_doc.aliases)
    parts << QString::fromStdString(alias);
  for (const auto &tag : m_doc.tags) {
    parts << QString::fromStdString(tag);
    const auto slash = tag.find_last_of('/');
    if (slash != std::string::npos && slash + 1 < tag.size()) {
      parts << QString::fromStdString(tag.substr(slash + 1));
    }
  }
  if (parts.isEmpty()) return std::nullopt;
  return parts.join(QLatin1Char(' '));
}

QString DocsRootItem::typeDisplayName() const { return tr("Document"); }

ImageURL DocsRootItem::iconUrl() const { return m_icon; }

AccessoryList DocsRootItem::accessories() const {
  return {{.text = tr("Docs"), .color = SemanticColor::TextMuted}};
}

std::vector<QString> DocsRootItem::keywords() const {
  std::vector<QString> keys;
  keys.reserve(m_doc.tags.size() + m_doc.aliases.size() * 2 + 1);
  for (const auto &tag : m_doc.tags)
    keys.emplace_back(QString::fromStdString(tag));
  for (const auto &alias : m_doc.aliases) {
    const QString text = QString::fromStdString(alias);
    keys.emplace_back(text);
    // Multi-word subnomes ("universidade paulista") also index each token.
    for (const auto &token : text.split(QLatin1Char(' '), Qt::SkipEmptyParts)) {
      if (token.compare(text, Qt::CaseInsensitive) != 0) keys.emplace_back(token);
    }
  }
  if (!m_doc.relPath.empty()) keys.emplace_back(QString::fromStdString(m_doc.relPath));
  return keys;
}

std::unique_ptr<ActionPanelState> DocsRootItem::newActionPanel(ApplicationContext *ctx,
                                                              const RootItemMetadata &metadata) const {
  auto panel = std::make_unique<ListActionPanelState>();
  auto *main = panel->createSection();
  auto *itemSection = panel->createSection();

  main->addAction(new OpenDocsFileAction(m_absolutePath));

  for (const auto action :
       RootSearchActionGenerator::generateActions(*this, *ctx->services->rootItemManager())) {
    itemSection->addAction(action);
  }

  panel->setTitle(title());
  return panel;
}

DocsRootProvider::DocsRootProvider(std::shared_ptr<DocsStore> store) : m_store(std::move(store)) {
  m_debounce.setSingleShot(true);
  m_debounce.setInterval(250);
  connect(&m_debounce, &QTimer::timeout, this, [this]() { emit itemsChanged(); });
  watchDatabase();
}

void DocsRootProvider::watchDatabase() {
  const auto path = m_store->databasePath();
  const QString db = QString::fromStdString(path.string());
  const QString wal = db + QStringLiteral("-wal");

  m_watcher.removePaths(m_watcher.files());
  if (QFile::exists(db)) m_watcher.addPath(db);
  if (QFile::exists(wal)) m_watcher.addPath(wal);

  // Also watch the parent directory so a newly created docs.db is picked up.
  const QString dir = QString::fromStdString(path.parent_path().string());
  if (!dir.isEmpty() && QFileInfo::exists(dir) && !m_watcher.directories().contains(dir)) {
    m_watcher.addPath(dir);
  }

  connect(&m_watcher, &QFileSystemWatcher::fileChanged, this, [this](const QString &) { scheduleReload(); },
          Qt::UniqueConnection);
  connect(&m_watcher, &QFileSystemWatcher::directoryChanged, this,
          [this](const QString &) {
            watchDatabase();
            scheduleReload();
          },
          Qt::UniqueConnection);
}

void DocsRootProvider::scheduleReload() {
  // SQLite may rewrite the path; re-add so the next change is still seen.
  watchDatabase();
  m_debounce.start();
}

QString DocsRootProvider::uniqueId() const { return QStringLiteral("docs"); }

QString DocsRootProvider::displayName() const { return tr("Docs"); }

QString DocsRootProvider::description() const {
  return tr("Documents indexed by the Atena Docs app on this machine.");
}

ImageURL DocsRootProvider::icon() const {
  return ImageURL::builtin(BuiltinIcon::BlankDocument).setBackgroundTint(SemanticColor::Blue);
}

RootProvider::Type DocsRootProvider::type() const { return RootProvider::Type::GroupProvider; }

std::vector<std::shared_ptr<RootItem>> DocsRootProvider::loadItems() const {
  const auto docs = m_store->listPresent();
  std::vector<std::shared_ptr<RootItem>> items;
  items.reserve(docs.size());
  for (const auto &doc : docs) {
    auto absolute = m_store->absolutePath(doc);
    items.emplace_back(std::make_shared<DocsRootItem>(doc, std::move(absolute)));
  }
  return items;
}
