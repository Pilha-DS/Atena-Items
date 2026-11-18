#pragma once

#include "root-search/docs/docs-store.hpp"
#include "services/root-item-manager/root-item-manager.hpp"
#include "ui/image/url.hpp"

#include <QCoreApplication>
#include <QFileSystemWatcher>
#include <QTimer>
#include <memory>

class DocsRootItem : public RootItem {
  Q_DECLARE_TR_FUNCTIONS(DocsRootItem)

public:
  explicit DocsRootItem(DocsStore::Document doc, std::filesystem::path absolutePath);

  EntrypointId uniqueId() const override;
  QString title() const override;
  QString subtitle() const override;
  std::optional<QString> unlocalizedTitle() const override;
  QString typeDisplayName() const override;
  ImageURL iconUrl() const override;
  AccessoryList accessories() const override;
  std::vector<QString> keywords() const override;
  std::unique_ptr<ActionPanelState> newActionPanel(ApplicationContext *ctx,
                                                   const RootItemMetadata &metadata) const override;

private:
  DocsStore::Document m_doc;
  std::filesystem::path m_absolutePath;
  ImageURL m_icon;
};

class DocsRootProvider : public RootProvider {
  Q_DECLARE_TR_FUNCTIONS(DocsRootProvider)

public:
  explicit DocsRootProvider(std::shared_ptr<DocsStore> store = std::make_shared<DocsStore>());

  QString uniqueId() const override;
  QString displayName() const override;
  QString description() const override;
  ImageURL icon() const override;
  Type type() const override;
  std::vector<std::shared_ptr<RootItem>> loadItems() const override;

private:
  void watchDatabase();
  void scheduleReload();

  std::shared_ptr<DocsStore> m_store;
  QFileSystemWatcher m_watcher;
  QTimer m_debounce;
};
