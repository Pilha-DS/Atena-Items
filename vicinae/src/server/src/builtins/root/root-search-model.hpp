#pragma once
#include "builtins/root/root-search-sources.hpp"
#include "ui/views/section-list-model.hpp"
#include "services/calculator-service/abstract-calculator-backend.hpp"
#include "services/files-service/abstract-file-indexer.hpp"
#include <QFutureWatcher>
#include <QTimer>
#include <chrono>
#include <string>

class AppService;
class NewsService;
class RootItemManager;
class UpdateService;
class IamodRootSection;

namespace config {
class Manager;
}

class RootSearchModel : public SectionListModel {
  Q_OBJECT

public:
  using CalculatorWatcher = QFutureWatcher<AbstractCalculatorBackend::ComputeResult>;
  using FileSearchWatcher = QFutureWatcher<std::vector<IndexerFileResult>>;

  explicit RootSearchModel(const ViewScope &scope, QObject *parent = nullptr);
  ~RootSearchModel() override;

  Q_INVOKABLE void setFilter(const QString &text);
  void setSelectedIndex(int index) override;

  const RootItem *selectedRootItem() const;

private:
  enum class ContentStage { Idle, Documents, Media };

  void refresh();
  bool rerunSearch();
  void startCalculator();
  void startFileSearch();
  void handleFileSearchFinished();
  void handleFileSearchTimeout();
  void finishFileSearch(std::vector<IndexerFileResult> files);
  bool fileSearchApplicable() const;
  void refreshCalculator();

  void startContentSearch(ContentStage stage);
  void scheduleContentSearch(ContentStage stage);
  void showContentMatches(ContentStage stage, std::vector<content_search::Match> matches, bool finished);
  void stopSearch();

  static constexpr int MIN_FS_TEXT_LENGTH = 3;

  RootItemManager *m_manager;
  AppService *m_appDb;
  NewsService *m_newsService;
  UpdateService *m_updateService;
  CalculatorService *m_calculator;
  FileService *m_fileService;
  config::Manager *m_config;

  RootUpdateSection *m_updateSource;
  RootLinkSection *m_linkSource;
  RootCalculatorSection *m_calcSource;
  IamodRootSection *m_iamodSource;
  RootNewsSection *m_newsSource;
  RootFavoritesSection *m_favoritesSource;
  RootResultsSection *m_resultsSource;
  RootFilesSection *m_filesSource;
  RootContentSection *m_contentSource;
  RootContentSection *m_mediaSource;
  RootFallbackSection *m_fallbackSource;

  std::string m_query;
  QString m_lastCompleterItemId;

  QTimer m_fileSearchDebounce;
  QTimer m_contentDebounce;
  QTimer m_fileSearchDeadline;
  FileSearchWatcher m_fileWatcher;
  std::string m_calculatorSearchQuery;
  std::string m_fileSearchQuery;
  bool m_fileSearchEnabled = false;
  bool m_hasFileResults = false;

  ContentSearch *m_contentSearch;
  ContentStage m_contentStage = ContentStage::Idle;
  ContentStage m_pendingContentStage = ContentStage::Idle;
  std::chrono::steady_clock::time_point m_keywordsDeadline{};
};
