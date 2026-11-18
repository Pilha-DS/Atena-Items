#pragma once

#include <QFuture>
#include <QObject>
#include <QPointer>
#include <QQuickItem>
#include <QUrl>
#include <QVariantList>
#include <QtQml/qqmlregistration.h>
#include <expected>
#include <optional>
#include <vector>
#include "attachment-content.hpp"

class QFileInfo;

class AttachmentModel : public QObject {
  Q_OBJECT
  QML_ELEMENT
  Q_PROPERTY(QVariantList items READ items NOTIFY changed)
  Q_PROPERTY(int count READ count NOTIFY changed)
  Q_PROPERTY(bool ready READ ready NOTIFY changed)
  Q_PROPERTY(bool hasImages READ hasImages NOTIFY changed)
  Q_PROPERTY(QString error READ error NOTIFY changed)

public:
  Q_INVOKABLE void addFiles(const QStringList &paths);
  Q_INVOKABLE void addUrls(const QList<QUrl> &urls);
  Q_INVOKABLE bool acceptsUrls(const QList<QUrl> &urls) const;
  Q_INVOKABLE void remove(int id);
  Q_INVOKABLE void clear();
  Q_INVOKABLE bool paste();

signals:
  void changed();

public:
  explicit AttachmentModel(QObject *parent = nullptr);
  QVariantList items() const;
  int count() const { return static_cast<int>(m_entries.size()); }
  bool ready() const;
  bool loading() const;
  bool hasImages() const;
  QString error() const { return m_error; }
  std::vector<AttachmentContent> take();
  // Moves the attachments that are already loaded, leaving behind errors and files still being read.
  std::vector<AttachmentContent> takeReady();
  // Adds attachments that were read elsewhere, they are valid as soon as this returns.
  void adopt(std::vector<AttachmentContent> attachments);

  using Result = std::expected<FileAttachment, QString>;
  // Reads an image, a PDF or a UTF-8 text file into an attachment, safe to call off the main thread
  static Result readFile(const QString &path);

private:
  struct Entry {
    int id;
    QString name;
    std::optional<QString> path;
    AttachmentContent attachment;
    QString error;
    bool loading = true;
  };
  bool canAdd();
  void load(Entry entry, QFuture<Result> future);
  static Result prepareImage(QImage image, std::string name);
  static Result readPdf(const QFileInfo &info, const QByteArray &data);
  std::vector<Entry> m_entries;
  int m_nextId = 0;
  QString m_error;
};

class AttachmentPasteHandler : public QObject {
  Q_OBJECT
  QML_ELEMENT
  Q_PROPERTY(AttachmentModel *model MEMBER m_model)
  Q_PROPERTY(QQuickItem *target READ target WRITE setTarget NOTIFY targetChanged)
  Q_PROPERTY(bool enabled MEMBER m_enabled)

signals:
  void targetChanged();

public:
  using QObject::QObject;
  QQuickItem *target() const { return m_target; }
  void setTarget(QQuickItem *target);
  bool eventFilter(QObject *object, QEvent *event) override;

private:
  QPointer<AttachmentModel> m_model;
  QPointer<QQuickItem> m_target;
  bool m_enabled = true;
};
