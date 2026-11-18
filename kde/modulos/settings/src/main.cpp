// atena-settings: one window for the Plasma configuration screens, grouped by
// the layout in layout.conf. It hosts the native KCMs (QML and QWidget); it does
// not reimplement any setting.

#include <QApplication>
#include <QAbstractButton>
#include <QCommandLineOption>
#include <QCommandLineParser>
#include <QCoreApplication>
#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QFont>
#include <QHBoxLayout>
#include <QHash>
#include <QIcon>
#include <QLabel>
#include <QLibraryInfo>
#include <QListWidget>
#include <QListWidgetItem>
#include <QPainter>
#include <QPixmap>
#include <QPushButton>
#include <QScrollArea>
#include <QSet>
#include <QSize>
#include <QStackedWidget>
#include <QStandardPaths>
#include <QStringList>
#include <QTabBar>
#include <QTextStream>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

#include <KAbstractConfigModule>
#include <KCModule>
#include <KCModuleLoader>
#include <KColorScheme>
#include <KPluginMetaData>

#include <cstdio>
#include <functional>
#include <memory>
#include <vector>

namespace {

// One KCM inside a tab.
struct KcmRef {
    QString id;
    QString label;
    // Empty means "use the plugin's own icon".
    QString icon;
};

// A tab: the sub-menu at the top. It holds one or more KCMs.
struct Tab {
    QString name;
    // Empty means "use the icon of the tab's only KCM", so a tab that wraps a
    // single KCM needs nothing extra in layout.conf.
    QString icon;
    QVector<KcmRef> kcms;
};

// A group: one entry in the left-hand menu.
struct Group {
    QString name;
    QString icon;
    QVector<Tab> tabs;
};

// Placeholder shown while a KCM is being built. A rounded block in the
// alternate color gives the page its shape from the first frame; the text is
// only there to explain the delay, so it stays dim.
class LoadingBlock : public QWidget
{
public:
    explicit LoadingBlock(QWidget *parent = nullptr)
        : QWidget(parent)
    {
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        setFixedHeight(88);
        auto *layout = new QVBoxLayout(this);
        layout->setContentsMargins(16, 0, 16, 0);
        m_label = new QLabel(QStringLiteral("Carregando…"), this);
        m_label->setAlignment(Qt::AlignCenter);
        m_label->setWordWrap(true);
        layout->addWidget(m_label);
        QPalette palette = m_label->palette();
        palette.setColor(QPalette::WindowText, palette.color(QPalette::Disabled, QPalette::WindowText));
        m_label->setPalette(palette);
    }

    // Failures reuse the block: the message is the only thing left to show.
    void setMessage(const QString &text)
    {
        m_label->setText(text);
        QPalette palette = m_label->palette();
        // Read from the color scheme instead of hardcoding red: the failure
        // text follows whatever scheme the desktop is using.
        const KColorScheme scheme(QPalette::Normal, KColorScheme::Window);
        palette.setColor(QPalette::WindowText, scheme.foreground(KColorScheme::NegativeText).color());
        m_label->setPalette(palette);
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        QColor fill = palette().color(QPalette::AlternateBase);
        fill.setAlpha(140);
        painter.setPen(Qt::NoPen);
        painter.setBrush(fill);
        painter.drawRoundedRect(QRectF(rect()).adjusted(0, 0, -1, -1), 8, 8);
    }

private:
    QLabel *m_label = nullptr;
};

// A KCM paints its own background (Base) and, when it is QML, its own scroll
// area. Wrapping it in a bordered card put a second frame around that and made
// one screen look like three nested boxes, so the page is the frame: the KCM
// gets the whole content area and nothing is drawn around it.
class KcmHost : public QWidget
{
public:
    explicit KcmHost(QWidget *parent = nullptr)
        : QWidget(parent)
    {
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
        m_layout = new QVBoxLayout(this);
        m_layout->setContentsMargins(0, 0, 0, 0);
        m_layout->setSpacing(0);
    }

    QVBoxLayout *body() const
    {
        return m_layout;
    }

private:
    QVBoxLayout *m_layout = nullptr;
};

// One item of the third navigation level. A rounded label instead of a native
// tab on purpose: a second QTabBar under the first reads as another set of tabs
// and the user loses track of where they are.
class SegmentButton : public QAbstractButton
{
public:
    explicit SegmentButton(QString text, QWidget *parent = nullptr)
        : QAbstractButton(parent)
    {
        setText(std::move(text));
        setCheckable(true);
        setCursor(Qt::PointingHandCursor);
        // Reachable by keyboard (Tab + Space), otherwise the third level would
        // be mouse-only.
        setFocusPolicy(Qt::StrongFocus);
        setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    }

    QSize sizeHint() const override
    {
        const QFontMetrics metrics(font());
        int width = kPadding * 2 + metrics.horizontalAdvance(text());
        if (!icon().isNull()) {
            width += kIconSize + kIconGap;
        }
        return {width, kHeight};
    }

protected:
    void enterEvent(QEnterEvent *) override
    {
        m_hover = true;
        update();
    }

    void leaveEvent(QEvent *) override
    {
        m_hover = false;
        update();
    }

    void focusInEvent(QFocusEvent *event) override
    {
        QAbstractButton::focusInEvent(event);
        update();
    }

    void focusOutEvent(QFocusEvent *event) override
    {
        QAbstractButton::focusOutEvent(event);
        update();
    }

    void paintEvent(QPaintEvent *) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        const QPalette pal = palette();

        QColor background = Qt::transparent;
        if (isChecked()) {
            background = pal.color(QPalette::Highlight);
        } else {
            // A faint pill even when idle: without it the row reads as a wall of
            // text instead of something clickable. Hover just makes it firmer.
            // Derived from Mid so it follows the color scheme.
            background = pal.color(QPalette::Mid);
            background.setAlpha(m_hover ? 95 : 55);
        }
        if (background.alpha() > 0) {
            painter.setPen(Qt::NoPen);
            painter.setBrush(background);
            painter.drawRoundedRect(QRectF(rect()).adjusted(0, 0, -1, -1), 7, 7);
        }
        if (hasFocus()) {
            QPen focusPen(pal.color(QPalette::Highlight), 1);
            painter.setPen(focusPen);
            painter.setBrush(Qt::NoBrush);
            painter.drawRoundedRect(QRectF(rect()).adjusted(1.5, 1.5, -2.5, -2.5), 6, 6);
        }

        const QColor foreground =
            isChecked() ? pal.color(QPalette::HighlightedText) : pal.color(QPalette::WindowText);
        int x = kPadding;
        if (!icon().isNull()) {
            const QPixmap pixmap = icon().pixmap(QSize(kIconSize, kIconSize), devicePixelRatioF());
            painter.drawPixmap(x, (height() - kIconSize) / 2, pixmap);
            x += kIconSize + kIconGap;
        }
        painter.setPen(foreground);
        const QFontMetrics metrics(font());
        const QRect textRect(x, 0, width() - x - kPadding, height());
        painter.drawText(textRect, Qt::AlignVCenter | Qt::AlignLeft,
                         metrics.elidedText(text(), Qt::ElideRight, textRect.width()));
    }

private:
    static constexpr int kHeight = 30;
    static constexpr int kPadding = 12;
    static constexpr int kIconSize = 16;
    static constexpr int kIconGap = 6;
    bool m_hover = false;
};

// The row of segment buttons. Wraps into more rows when the window is narrow,
// so nine chips do not turn into a horizontal scrollbar nobody notices.
class SegmentBar : public QWidget
{
public:
    explicit SegmentBar(QWidget *parent = nullptr)
        : QWidget(parent)
    {
        setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Minimum);
    }

    // Called with the index of the chip the user picked.
    std::function<void(int)> onChange;

    void addSegment(const QString &text, const QIcon &icon)
    {
        const int index = int(m_buttons.size());
        auto *button = new SegmentButton(text, this);
        button->setIcon(icon);
        m_buttons.push_back(button);
        connect(button, &QAbstractButton::clicked, this, [this, index] {
            setCurrent(index);
        });
        // The first chip has to look selected before anyone clicks it: the
        // stacked page already starts on index 0.
        button->setChecked(index == m_current);
        relayout();
    }

    void setCurrent(int index)
    {
        if (index < 0 || index >= m_buttons.size()) {
            return;
        }
        const bool changed = index != m_current;
        m_current = index;
        for (int i = 0; i < m_buttons.size(); ++i) {
            m_buttons[i]->setChecked(i == index);
        }
        if (changed && onChange) {
            onChange(index);
        }
    }

    int current() const
    {
        return m_current;
    }

    bool hasHeightForWidth() const override
    {
        return true;
    }

    int heightForWidth(int width) const override
    {
        return wrappedHeight(width);
    }

    QSize sizeHint() const override
    {
        const int width = this->width() > 0 ? this->width() : 400;
        return {width, wrappedHeight(width)};
    }

protected:
    void resizeEvent(QResizeEvent *) override
    {
        relayout();
    }

private:
    int wrappedHeight(int available) const
    {
        if (m_buttons.isEmpty()) {
            return 0;
        }
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (const SegmentButton *button : m_buttons) {
            const QSize hint = button->sizeHint();
            if (x > 0 && x + hint.width() > available) {
                x = 0;
                y += rowHeight + kGap;
                rowHeight = 0;
            }
            x += hint.width() + kGap;
            rowHeight = qMax(rowHeight, hint.height());
        }
        return y + rowHeight;
    }

    void relayout()
    {
        const int available = width();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (SegmentButton *button : m_buttons) {
            const QSize hint = button->sizeHint();
            if (x > 0 && x + hint.width() > available) {
                x = 0;
                y += rowHeight + kGap;
                rowHeight = 0;
            }
            button->setGeometry(x, y, hint.width(), hint.height());
            x += hint.width() + kGap;
            rowHeight = qMax(rowHeight, hint.height());
        }
        const int total = m_buttons.isEmpty() ? 0 : y + rowHeight;
        if (minimumHeight() != total) {
            setMinimumHeight(total);
        }
        updateGeometry();
    }

    static constexpr int kGap = 8;
    QVector<SegmentButton *> m_buttons;
    int m_current = 0;
};

QStringList pluginRoots()
{
    const QString plugins = QLibraryInfo::path(QLibraryInfo::PluginsPath);
    return {
        plugins + QStringLiteral("/plasma/kcms/systemsettings"),
        plugins + QStringLiteral("/plasma/kcms/systemsettings_qwidgets"),
        plugins + QStringLiteral("/dolphin/kcms"),
        plugins + QStringLiteral("/kf6/krunner/kcms"),
    };
}

QHash<QString, KPluginMetaData> loadCatalog()
{
    QHash<QString, KPluginMetaData> catalog;
    for (const QString &root : pluginRoots()) {
        if (!QDir(root).exists()) {
            continue;
        }
        const auto plugins = KPluginMetaData::findPlugins(root);
        for (const KPluginMetaData &meta : plugins) {
            catalog.insert(meta.pluginId(), meta);
        }
    }
    return catalog;
}

// The plugin's own icon, used whenever layout.conf does not name one.
QString pluginIcon(const QHash<QString, KPluginMetaData> &catalog, const QString &id)
{
    const auto it = catalog.constFind(id);
    if (it == catalog.constEnd()) {
        return QString();
    }
    return it->iconName();
}

QString kcmIcon(const KcmRef &ref, const QHash<QString, KPluginMetaData> &catalog)
{
    return ref.icon.isEmpty() ? pluginIcon(catalog, ref.id) : ref.icon;
}

QString tabIcon(const Tab &tab, const QHash<QString, KPluginMetaData> &catalog)
{
    if (!tab.icon.isEmpty()) {
        return tab.icon;
    }
    // A tab wrapping several unrelated KCMs has no icon of its own; borrowing
    // the first one would be misleading, so it stays text-only until
    // layout.conf names one.
    return tab.kcms.size() == 1 ? pluginIcon(catalog, tab.kcms.first().id) : QString();
}

// layout.conf lives next to the source, which for a dev build is the parent of
// the build directory. The installed copy (a user-editable override) lives in
// the XDG data dir, so all three are tried and the binary stays relocatable.
QString defaultLayoutPath()
{
    const QStringList candidates = {
        QCoreApplication::applicationDirPath() + QStringLiteral("/layout.conf"),
        QCoreApplication::applicationDirPath() + QStringLiteral("/../layout.conf"),
        QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation)
            + QStringLiteral("/atena-settings/layout.conf"),
    };
    for (const QString &candidate : candidates) {
        if (QFileInfo::exists(candidate)) {
            return QFileInfo(candidate).absoluteFilePath();
        }
    }
    return candidates.first();
}

// "Rótulo @ icone" carries an optional icon at the end of any line (group, tab
// or KCM). It is read before the "=" so the two never collide: the icon is
// purely decorative and a wrong name shows as a blank square, never as a
// missing tab.
QString takeIconSuffix(QString &text)
{
    const int at = text.lastIndexOf(QLatin1Char('@'));
    if (at < 0) {
        return QString();
    }
    const QString icon = text.mid(at + 1).trimmed();
    text = text.left(at).trimmed();
    return icon;
}

bool parseLayout(const QString &path, QVector<Group> &groups, QString *error)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        *error = QStringLiteral("não abri %1").arg(path);
        return false;
    }

    QTextStream in(&file);
    int groupIndex = -1;
    int tabIndex = -1;
    int lineNumber = 0;

    while (!in.atEnd()) {
        const QString line = in.readLine();
        ++lineNumber;
        QString text = line.trimmed();
        if (text.isEmpty() || text.startsWith(QLatin1Char('#'))) {
            continue;
        }
        const QString icon = takeIconSuffix(text);
        if (text.isEmpty()) {
            *error = QStringLiteral("linha %1: sobrou só um ícone, sem rótulo").arg(lineNumber);
            return false;
        }

        // Indentation is what separates a tab from the KCMs inside it, so it is
        // read before trimming.
        const bool indented = line.startsWith(QLatin1Char(' ')) || line.startsWith(QLatin1Char('\t'));

        if (text.startsWith(QLatin1Char('[')) && text.endsWith(QLatin1Char(']'))) {
            Group group;
            group.name = text.mid(1, text.size() - 2).trimmed();
            group.icon = icon;
            if (group.name.isEmpty()) {
                *error = QStringLiteral("linha %1: grupo sem nome").arg(lineNumber);
                return false;
            }
            groups.push_back(group);
            groupIndex = int(groups.size()) - 1;
            tabIndex = -1;
            continue;
        }

        if (groupIndex < 0) {
            *error = QStringLiteral("linha %1: item fora de um [grupo]").arg(lineNumber);
            return false;
        }

        const int equal = text.indexOf(QLatin1Char('='));
        const QString left = equal < 0 ? text : text.left(equal).trimmed();
        const QString right = equal < 0 ? QString() : text.mid(equal + 1).trimmed();

        if (indented) {
            if (tabIndex < 0) {
                *error = QStringLiteral("linha %1: KCM antes de uma aba").arg(lineNumber);
                return false;
            }
            if (equal < 0 || right.isEmpty()) {
                *error = QStringLiteral("linha %1: esperado \"rótulo = kcm_id\"").arg(lineNumber);
                return false;
            }
            groups[groupIndex].tabs[tabIndex].kcms.push_back({right, left, icon});
            continue;
        }

        // A tab may carry its only KCM inline ("Aba = kcm_id") or declare itself
        // now and list the KCMs indented below.
        Tab tab;
        tab.name = left;
        tab.icon = icon;
        if (tab.name.isEmpty()) {
            *error = QStringLiteral("linha %1: aba sem nome").arg(lineNumber);
            return false;
        }
        if (!right.isEmpty()) {
            // The icon on an "Aba = kcm_id" line belongs to the tab; the KCM
            // keeps its plugin icon.
            tab.kcms.push_back({right, left, QString()});
        }
        groups[groupIndex].tabs.push_back(tab);
        tabIndex = int(groups[groupIndex].tabs.size()) - 1;
    }

    if (groups.isEmpty()) {
        *error = QStringLiteral("layout vazio: %1").arg(path);
        return false;
    }
    for (const Group &group : groups) {
        if (group.tabs.isEmpty()) {
            *error = QStringLiteral("grupo %1 sem abas").arg(group.name);
            return false;
        }
        for (const Tab &tab : group.tabs) {
            if (tab.kcms.isEmpty()) {
                *error = QStringLiteral("aba %1 sem KCM").arg(tab.name);
                return false;
            }
        }
    }
    return true;
}

QStringList layoutIds(const QVector<Group> &groups)
{
    QStringList ids;
    for (const Group &group : groups) {
        for (const Tab &tab : group.tabs) {
            for (const KcmRef &kcm : tab.kcms) {
                ids.push_back(kcm.id);
            }
        }
    }
    return ids;
}

// Some KCMs reach another screen by launching the Plasma settings binary
// (`QProcess::startDetached("systemsettings", {"kcm_kwin_effects"})`, as
// kcm_animations does for "Mais opções de efeitos"). The installer leaves a
// `systemsettings` shim next to this binary; putting that directory first in
// PATH makes those children resolve to it and come back to this window. Only
// our own children see the change: the session keeps the Plasma binary.
void preferOwnShim()
{
    const QString selfDir = QFileInfo(QCoreApplication::applicationFilePath()).absolutePath();
    if (!QFileInfo::exists(selfDir + QStringLiteral("/systemsettings"))) {
        return;
    }
    const QString current = qEnvironmentVariable("PATH");
    if (current.split(QLatin1Char(':'), Qt::SkipEmptyParts).value(0) == selfDir) {
        return;
    }
    qputenv("PATH", selfDir.toLocal8Bit() + ':' + current.toLocal8Bit());
}

// A KCM named on the command line, the way `systemsettings kcm_x` names one.
bool resolveKcmId(const QString &id, const QVector<Group> &groups, int &groupIndex, int &tabIndex, int &subIndex)
{
    for (int g = 0; g < groups.size(); ++g) {
        for (int t = 0; t < groups.at(g).tabs.size(); ++t) {
            const auto &kcms = groups.at(g).tabs.at(t).kcms;
            for (int k = 0; k < kcms.size(); ++k) {
                if (kcms.at(k).id == id) {
                    groupIndex = g;
                    tabIndex = t;
                    subIndex = kcms.size() > 1 ? k : -1;
                    return true;
                }
            }
        }
    }
    return false;
}

// A page the user asked for by id but the map does not cover (a KCM that a
// redirect points at, or one added by a Plasma update). It gets its own group
// instead of being refused: the alternative is a dead end, and `--check` is
// where the layout gets fixed.
bool appendKcmPage(QVector<Group> &groups, const QHash<QString, KPluginMetaData> &catalog, const QString &id,
                   int &groupIndex, int &tabIndex, int &subIndex)
{
    const auto meta = catalog.constFind(id);
    if (meta == catalog.constEnd()) {
        return false;
    }
    Tab tab;
    tab.name = meta->name().isEmpty() ? id : meta->name();
    tab.kcms.push_back({id, tab.name, QString()});

    Group group;
    group.name = QStringLiteral("Fora do layout");
    group.icon = QStringLiteral("preferences-other");
    group.tabs.push_back(tab);
    groups.push_back(group);
    groupIndex = int(groups.size()) - 1;
    tabIndex = 0;
    subIndex = -1;
    return true;
}

class SettingsWindow : public QWidget
{
public:
    SettingsWindow(QVector<Group> groups, QHash<QString, KPluginMetaData> catalog, bool eager, QWidget *parent = nullptr);

    bool hasError() const { return m_hasError; }
    // subIndex < 0 means "the tab itself", leaving its sub-page untouched.
    bool select(int groupIndex, int tabIndex, int subIndex = -1);
    // True while any page still has KCMs waiting to be poured in.
    bool isLoading() const;

private:
    struct Module {
        KCModule *module = nullptr;
        QString id;
        QString label;
        // Which sub-page of the tab it belongs to, so "Restaurar padrões" only
        // touches what is on screen.
        int slot = 0;
    };

    // A tab is built as a skeleton (one placeholder per KCM) and filled on
    // demand. Nine KCMs in one go froze the window for ~6s; building only the
    // visible sub-page keeps the click responsive and lets the user leave before
    // the rest is built.
    //
    // A tab with more than one KCM gets a third navigation level: one chip per
    // KCM (`nav` + `subPages`) instead of stacking every KCM in one long form.
    struct KcmSlot {
        // Host of one KCM in the page. Not a Card: see KcmHost.
        QWidget *host = nullptr;
        QVBoxLayout *body = nullptr;
        bool started = false;
    };
    struct TabPage {
        QVector<KcmRef> refs;
        QVector<KcmSlot> kcms;
        SegmentBar *nav = nullptr;
        QStackedWidget *subPages = nullptr;
        int current = 0;
        bool loading = false;
        std::vector<Module> modules;
    };

    static QString keyOf(int groupIndex, int tabIndex)
    {
        return QString::number(groupIndex) + QLatin1Char('/') + QString::number(tabIndex);
    }

    void showGroup(int index);
    void showTab(int index);
    QWidget *buildTabPage(int groupIndex, int tabIndex);
    void scheduleLoad(const QString &key);
    void loadNext(const QString &key);
    void applyAll();
    void resetCurrentTab();
    void updateStatus();
    // True when the slot still needs a module built. Lazy pages only care about
    // the sub-page on screen; eager mode (tests, --select) wants all of them.
    bool slotWanted(const TabPage &page, int index) const;
    bool wantsWork(const TabPage &page) const;

    QVector<Group> m_groups;
    QHash<QString, KPluginMetaData> m_catalog;
    QListWidget *m_menu = nullptr;
    QTabBar *m_tabs = nullptr;
    QStackedWidget *m_pages = nullptr;
    QLabel *m_status = nullptr;
    QPushButton *m_apply = nullptr;
    QPushButton *m_defaults = nullptr;
    QHash<QString, TabPage> m_tabPages;
    QHash<QString, QWidget *> m_built;
    QString m_currentKey;
    int m_currentGroup = -1;
    bool m_hasError = false;
    // Tests and --select need every KCM loaded before the process exits, so
    // there the skeleton is filled synchronously instead of across turns.
    bool m_eager = false;
};

SettingsWindow::SettingsWindow(QVector<Group> groups, QHash<QString, KPluginMetaData> catalog, bool eager, QWidget *parent)
    : QWidget(parent)
    , m_groups(std::move(groups))
    , m_catalog(std::move(catalog))
    , m_eager(eager)
{
    setWindowTitle(QStringLiteral("Configurações"));
    // Same name as the launcher entry: a window without an icon gets the
    // toolkit's default, which is the one thing that never matches the theme.
    setWindowIcon(QIcon::fromTheme(QStringLiteral("preferences-system")));

    auto *root = new QHBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    m_menu = new QListWidget(this);
    m_menu->setFixedWidth(212);
    m_menu->setFrameShape(QFrame::NoFrame);
    m_menu->setIconSize(QSize(22, 22));
    m_menu->setSpacing(2);
    m_menu->setUniformItemSizes(true);
    for (const Group &group : m_groups) {
        auto *item = new QListWidgetItem(QIcon::fromTheme(group.icon), group.name);
        m_menu->addItem(item);
    }
    root->addWidget(m_menu);

    auto *right = new QVBoxLayout;
    right->setContentsMargins(16, 16, 16, 12);
    right->setSpacing(12);
    root->addLayout(right, 1);

    m_tabs = new QTabBar(this);
    m_tabs->setExpanding(false);
    m_tabs->setDrawBase(false);
    m_tabs->setDocumentMode(true);
    m_tabs->setIconSize(QSize(18, 18));
    right->addWidget(m_tabs);

    // A hairline closes the tab row so the navigation does not float over the
    // page. Palette Mid follows the color scheme instead of a fixed gray.
    auto *tabRule = new QWidget(this);
    tabRule->setFixedHeight(1);
    tabRule->setAutoFillBackground(true);
    QPalette tabRulePalette = tabRule->palette();
    tabRulePalette.setColor(QPalette::Window, tabRulePalette.color(QPalette::Mid));
    tabRule->setPalette(tabRulePalette);
    right->addWidget(tabRule);

    m_pages = new QStackedWidget(this);
    right->addWidget(m_pages, 1);

    // Same hairline treatment as the tab row: the footer is chrome, not content.
    auto *footerRule = new QWidget(this);
    footerRule->setFixedHeight(1);
    footerRule->setAutoFillBackground(true);
    QPalette footerRulePalette = footerRule->palette();
    footerRulePalette.setColor(QPalette::Window, footerRulePalette.color(QPalette::Mid));
    footerRule->setPalette(footerRulePalette);
    right->addWidget(footerRule);

    auto *footer = new QHBoxLayout;
    footer->setSpacing(8);
    m_status = new QLabel(this);
    footer->addWidget(m_status);
    footer->addStretch(1);
    m_defaults = new QPushButton(QIcon::fromTheme(QStringLiteral("edit-undo")), QStringLiteral("Restaurar padrões"), this);
    m_apply = new QPushButton(QIcon::fromTheme(QStringLiteral("dialog-ok-apply")), QStringLiteral("Aplicar"), this);
    m_apply->setEnabled(false);
    footer->addWidget(m_defaults);
    footer->addWidget(m_apply);
    right->addLayout(footer);

    connect(m_menu, &QListWidget::currentRowChanged, this, &SettingsWindow::showGroup);
    connect(m_tabs, &QTabBar::currentChanged, this, &SettingsWindow::showTab);
    connect(m_apply, &QPushButton::clicked, this, &SettingsWindow::applyAll);
    connect(m_defaults, &QPushButton::clicked, this, &SettingsWindow::resetCurrentTab);

    m_menu->setCurrentRow(0);
}

void SettingsWindow::showGroup(int index)
{
    m_currentGroup = index;
    m_currentKey.clear();

    while (m_tabs->count() > 0) {
        m_tabs->removeTab(0);
    }
    if (index < 0 || index >= m_groups.size()) {
        return;
    }
    const QVector<Tab> &tabs = m_groups.at(index).tabs;
    for (const Tab &tab : tabs) {
        const int tabIndex = m_tabs->addTab(tab.name);
        const QString icon = tabIcon(tab, m_catalog);
        if (!icon.isEmpty()) {
            m_tabs->setTabIcon(tabIndex, QIcon::fromTheme(icon));
        }
    }
    if (m_tabs->count() > 0) {
        m_tabs->setCurrentIndex(0);
    }
}

void SettingsWindow::showTab(int index)
{
    if (m_currentGroup < 0 || index < 0) {
        return;
    }
    const QString key = keyOf(m_currentGroup, index);
    if (!m_built.contains(key)) {
        auto *page = buildTabPage(m_currentGroup, index);
        m_pages->addWidget(page);
        m_built.insert(key, page);
    }
    m_currentKey = key;
    m_pages->setCurrentWidget(m_built.value(key));

    // Coming back to a page that was left mid-load, or to a page whose visible
    // sub-tab was never built: start (or resume) it.
    const auto state = m_tabPages.find(key);
    if (state != m_tabPages.end() && !state->loading && wantsWork(state.value())) {
        scheduleLoad(key);
    }
    updateStatus();
}

void SettingsWindow::scheduleLoad(const QString &key)
{
    if (m_eager) {
        loadNext(key);
        return;
    }
    // The first module waits a couple of frames so the page paints before a
    // plugin starts blocking the thread; later ones (a chip the user clicked)
    // go right away, since the page is already on screen.
    QTimer::singleShot(30, this, [this, key] {
        loadNext(key);
    });
}

QWidget *SettingsWindow::buildTabPage(int groupIndex, int tabIndex)
{
    const Tab &tab = m_groups.at(groupIndex).tabs.at(tabIndex);
    const QString key = keyOf(groupIndex, tabIndex);

    auto *page = new QWidget;
    auto *outer = new QVBoxLayout(page);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);

    TabPage state;
    state.refs = tab.kcms;

    // A tab with several KCMs gets a third navigation level. Without it the
    // KCMs pile up in one page and the user has to scroll past "Energia móvel"
    // to find out it is a different screen.
    const bool hasSubNav = tab.kcms.size() > 1;
    SegmentBar *nav = nullptr;
    QStackedWidget *subPages = nullptr;

    if (hasSubNav) {
        // Centered with the content below, so the chips start on the same line
        // as the screen instead of hugging the window edge.
        auto *navRow = new QWidget(page);
        auto *navLayout = new QHBoxLayout(navRow);
        navLayout->setContentsMargins(10, 10, 10, 6);
        navLayout->setSpacing(0);
        nav = new SegmentBar(navRow);
        nav->setMaximumWidth(980);
        navLayout->addStretch(1);
        navLayout->addWidget(nav, 100);
        navLayout->addStretch(1);
        outer->addWidget(navRow);

        subPages = new QStackedWidget(page);
        outer->addWidget(subPages, 1);
        state.nav = nav;
        state.subPages = subPages;
    }

    for (const KcmRef &ref : tab.kcms) {
        // One scroll area per KCM and one KCM per scroll area: with sub-tabs
        // only one is visible at a time, and each keeps its own scroll position.
        auto *scroll = new QScrollArea(page);
        scroll->setWidgetResizable(true);
        scroll->setFrameShape(QFrame::NoFrame);

        // The content lives in a column with a maximum width, centered by the
        // two stretches. Without the cap a form stretches across a maximized
        // window and labels drift far from their controls.
        auto *content = new QWidget;
        auto *column = new QHBoxLayout(content);
        column->setContentsMargins(0, 0, 0, 0);
        column->setSpacing(0);

        auto *inner = new QWidget(content);
        inner->setMaximumWidth(980);
        column->addStretch(1);
        column->addWidget(inner, 100);
        column->addStretch(1);
        scroll->setWidget(content);

        auto *contentLayout = new QVBoxLayout(inner);
        // The page is the frame: the KCM keeps its own margins, so ours are just
        // the window's breathing room.
        contentLayout->setContentsMargins(12, hasSubNav ? 6 : 12, 12, 16);
        contentLayout->setSpacing(0);

        auto *host = new KcmHost(inner);
        auto *waiting = new LoadingBlock(host);
        waiting->setObjectName(QStringLiteral("atena-waiting"));
        host->body()->addWidget(waiting);
        // Stretch 1, no trailing spacer: a short KCM still fills the window
        // instead of floating at the top of an empty page.
        contentLayout->addWidget(host, 1);

        if (hasSubNav) {
            subPages->addWidget(scroll);
            const QString iconName = kcmIcon(ref, m_catalog);
            nav->addSegment(ref.label, iconName.isEmpty() ? QIcon() : QIcon::fromTheme(iconName));
        } else {
            outer->addWidget(scroll);
        }

        KcmSlot slot;
        slot.host = host;
        slot.body = host->body();
        state.kcms.push_back(slot);
    }

    if (hasSubNav) {
        // The chips are the only way to reach the other KCMs, so the wiring
        // lives with the page. The lambda looks the page up by key instead of
        // capturing `state`, which is copied into the map afterwards.
        nav->onChange = [this, key](int index) {
            const auto it = m_tabPages.find(key);
            if (it == m_tabPages.end() || !it->subPages) {
                return;
            }
            it->current = index;
            it->subPages->setCurrentIndex(index);
            scheduleLoad(key);
            updateStatus();
        };
    }

    m_tabPages.insert(key, state);
    return page;
}

bool SettingsWindow::slotWanted(const TabPage &page, int index) const
{
    if (index < 0 || index >= page.kcms.size()) {
        return false;
    }
    if (page.kcms.at(index).started) {
        return false;
    }
    // Lazy mode builds only the sub-page on screen; the others wait for a click.
    if (!m_eager && index != page.current) {
        return false;
    }
    return true;
}

bool SettingsWindow::wantsWork(const TabPage &page) const
{
    if (m_eager) {
        for (int i = 0; i < page.kcms.size(); ++i) {
            if (slotWanted(page, i)) {
                return true;
            }
        }
        return false;
    }
    return slotWanted(page, page.current);
}

void SettingsWindow::loadNext(const QString &key)
{
    auto it = m_tabPages.find(key);
    if (it == m_tabPages.end()) {
        return;
    }
    TabPage &state = it.value();

    // Loading a page the user already left wastes seconds of CPU; showTab
    // restarts it when (and if) the page comes back.
    if (!m_eager && m_currentKey != key) {
        state.loading = false;
        return;
    }

    int index = -1;
    if (slotWanted(state, state.current)) {
        index = state.current;
    } else if (m_eager) {
        for (int i = 0; i < state.kcms.size(); ++i) {
            if (slotWanted(state, i)) {
                index = i;
                break;
            }
        }
    }
    if (index < 0) {
        state.loading = false;
        updateStatus();
        return;
    }

    state.loading = true;
    // Marked before the module is built: a second schedule while this one runs
    // must not build the same KCM twice.
    state.kcms[index].started = true;
    const KcmRef ref = state.refs.at(index);
    // findChild<LoadingBlock *> would need a Q_OBJECT macro, which moc does not
    // process for a class in an anonymous namespace; the widget is ours, so a
    // plain static_cast after the named lookup is enough.
    auto *waiting = static_cast<LoadingBlock *>(state.kcms.at(index).host->findChild<QWidget *>(
        QStringLiteral("atena-waiting")));

    QString failure;
    const auto meta = m_catalog.constFind(ref.id);
    if (meta == m_catalog.constEnd()) {
        failure = QStringLiteral("KCM não encontrado nesta máquina: %1").arg(ref.id);
    } else {
        // The parent stays null on purpose: a QWidget-based KCM may install its
        // own layout on the widget it receives. Adding the returned widget to
        // the layout reparents it anyway.
        KCModule *module = KCModuleLoader::loadModule(*meta, nullptr);
        if (!module || !module->widget()) {
            delete module;
            failure = QStringLiteral("Falha ao carregar: %1").arg(ref.id);
        } else {
            module->load();
            QWidget *widget = module->widget();
            widget->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
            delete waiting;
            state.kcms.at(index).body->addWidget(widget);
            connect(module, &KAbstractConfigModule::needsSaveChanged, this, &SettingsWindow::updateStatus);
            state.modules.push_back({module, ref.id, ref.label, index});
        }
    }

    if (!failure.isEmpty()) {
        m_hasError = true;
        if (waiting) {
            waiting->setMessage(failure);
        }
    }

    if (m_eager) {
        // One frame at a time with no event loop: the recursion is bounded by
        // the number of KCMs in the tab (at most nine).
        loadNext(key);
        return;
    }
    scheduleLoad(key);
}

void SettingsWindow::applyAll()
{
    int saved = 0;
    for (auto &state : m_tabPages) {
        for (Module &module : state.modules) {
            if (module.module->needsSave()) {
                module.module->save();
                ++saved;
            }
        }
    }
    updateStatus();
}

void SettingsWindow::resetCurrentTab()
{
    const auto state = m_tabPages.find(m_currentKey);
    if (state == m_tabPages.end()) {
        return;
    }
    // Only the sub-page on screen: resetting the KCMs of the other chips would
    // change screens the user is not looking at.
    for (const Module &module : state->modules) {
        if (module.slot == state->current) {
            module.module->defaults();
        }
    }
    updateStatus();
}

void SettingsWindow::updateStatus()
{
    bool dirty = false;
    for (const auto &state : m_tabPages) {
        for (const Module &module : state.modules) {
            if (module.module->needsSave()) {
                dirty = true;
                break;
            }
        }
    }

    const auto current = m_tabPages.constFind(m_currentKey);
    const bool loading = current != m_tabPages.constEnd() && wantsWork(current.value());

    if (loading) {
        m_status->setText(QStringLiteral("Carregando…"));
    } else if (m_hasError) {
        m_status->setText(QStringLiteral("Algum KCM não carregou — veja a aba"));
    } else if (dirty) {
        m_status->setText(QStringLiteral("Alterações não aplicadas"));
    } else {
        m_status->setText(QStringLiteral("Tudo aplicado"));
    }
    m_apply->setEnabled(dirty);
    // Defaults would only reach the KCMs already loaded, so it waits until the
    // visible sub-page is in place.
    m_defaults->setEnabled(!loading);
}

bool SettingsWindow::isLoading() const
{
    // Only the visible page loads: pages left behind are paused on purpose, so
    // counting them would report "busy" forever.
    const auto state = m_tabPages.constFind(m_currentKey);
    return state != m_tabPages.constEnd() && wantsWork(state.value());
}

bool SettingsWindow::select(int groupIndex, int tabIndex, int subIndex)
{
    if (groupIndex < 0 || groupIndex >= m_groups.size()) {
        return false;
    }
    const Tab &tab = m_groups.at(groupIndex).tabs.at(tabIndex);
    Q_UNUSED(tab);
    m_menu->setCurrentRow(groupIndex);
    m_tabs->setCurrentIndex(tabIndex);
    if (m_currentKey != keyOf(groupIndex, tabIndex)) {
        return false;
    }
    if (subIndex < 0) {
        return true;
    }
    const auto it = m_tabPages.find(m_currentKey);
    if (it == m_tabPages.end()) {
        return false;
    }
    // "Aba de um KCM só" has no chips; sub-tab 1 is the tab itself.
    if (!it->nav) {
        return subIndex == 0;
    }
    if (subIndex >= it->kcms.size()) {
        return false;
    }
    it->nav->setCurrent(subIndex);
    return true;
}

int printLayout(const QVector<Group> &groups, const QHash<QString, KPluginMetaData> &catalog)
{
    int missing = 0;
    int noIcon = 0;
    for (const Group &group : groups) {
        std::printf("%s  %s\n", qPrintable(group.name), qPrintable(group.icon));
        if (group.icon.isEmpty()) {
            ++noIcon;
        }
        for (const Tab &tab : group.tabs) {
            const QString icon = tabIcon(tab, catalog);
            std::printf("  %-30s %s\n", qPrintable(tab.name), qPrintable(icon));
            if (icon.isEmpty()) {
                ++noIcon;
            }
            for (const KcmRef &kcm : tab.kcms) {
                const bool known = catalog.contains(kcm.id);
                if (!known) {
                    ++missing;
                }
                std::printf("    %-28s %-34s %-24s %s\n", qPrintable(kcm.id), qPrintable(kcm.label),
                            qPrintable(kcmIcon(kcm, catalog)), known ? "" : "AUSENTE");
            }
        }
    }
    const QStringList ids = layoutIds(groups);
    int tabs = 0;
    for (const Group &group : groups) {
        tabs += int(group.tabs.size());
    }
    std::printf("\n%d grupos, %d abas, %d KCMs no layout, %d ausentes nesta máquina, %d sem ícone\n",
                int(groups.size()), tabs, int(ids.size()), missing, noIcon);
    return missing == 0 ? 0 : 1;
}

// Every icon is a theme name; a name the active theme does not ship draws a
// blank square, and nobody notices until that screen is opened. Needs a
// QGuiApplication (QIcon has no meaning without one), so it runs with a
// display like --measure, just without opening a window.
int checkIcons(const QVector<Group> &groups, const QHash<QString, KPluginMetaData> &catalog)
{
    QStringList names;
    QSet<QString> seen;
    const auto add = [&names, &seen](const QString &name) {
        if (!name.isEmpty() && !seen.contains(name)) {
            seen.insert(name);
            names.push_back(name);
        }
    };
    for (const Group &group : groups) {
        add(group.icon);
        for (const Tab &tab : group.tabs) {
            add(tabIcon(tab, catalog));
            for (const KcmRef &kcm : tab.kcms) {
                add(kcmIcon(kcm, catalog));
            }
        }
    }

    int bad = 0;
    for (const QString &name : names) {
        if (QIcon::fromTheme(name).isNull()) {
            std::printf("sem arquivo: %s\n", qPrintable(name));
            ++bad;
        }
    }
    std::printf("\n%d nomes de ícone, %d que o tema %s não tem\n", int(names.size()), bad,
                qPrintable(QIcon::themeName()));
    return bad == 0 ? 0 : 1;
}

int checkLayout(const QVector<Group> &groups, const QHash<QString, KPluginMetaData> &catalog)
{
    int problems = 0;
    QStringList ids = layoutIds(groups);

    // Duplicate ids would load the same KCM twice and make Apply ambiguous.
    QSet<QString> seen;
    for (const QString &id : ids) {
        if (seen.contains(id)) {
            std::printf("duplicado:  %s\n", qPrintable(id));
            ++problems;
        }
        seen.insert(id);
    }

    for (const QString &id : ids) {
        if (!catalog.contains(id)) {
            std::printf("ausente:    %s (não existe nesta máquina)\n", qPrintable(id));
            ++problems;
        }
    }

    // The important one: a KCM that exists but is not in the layout would simply
    // vanish from the window, which is a removal nobody approved.
    for (auto it = catalog.constBegin(); it != catalog.constEnd(); ++it) {
        if (!seen.contains(it.key())) {
            std::printf("fora:       %s (%s)\n", qPrintable(it.key()), qPrintable(it.value().name()));
            ++problems;
        }
    }

    std::printf("\n%d KCMs na máquina, %d no layout, %d problemas\n", int(catalog.size()), int(ids.size()), problems);
    return problems == 0 ? 0 : 1;
}

} // namespace

// Accepts "2/3" (group/tab), "2/3/1" (group/tab/sub-tab) or the labels
// ("Básico/Bluetooth", or "Energia/Gerenciamento de energia/Energia móvel").
// subIndex stays -1 when only two parts are given. Returns false when the
// target does not exist, so --select and --measure print the same error.
static bool resolveTarget(const QString &arg, const QVector<Group> &groups, int &groupIndex, int &tabIndex,
                          int &subIndex)
{
    subIndex = -1;
    // A plugin id ("kcm_kwin_effects") is how the Plasma binary is asked for a
    // page from the command line, so it is accepted here too. No group name
    // looks like one, so trying it first cannot shadow the other formats.
    if (!arg.contains(QLatin1Char('/')) && resolveKcmId(arg, groups, groupIndex, tabIndex, subIndex)) {
        return true;
    }
    const QStringList parts = arg.split(QLatin1Char('/'));
    if (parts.size() != 2 && parts.size() != 3) {
        return false;
    }
    bool okGroup = false;
    bool okTab = false;
    const int groupNumber = parts.at(0).toInt(&okGroup);
    const int tabNumber = parts.at(1).toInt(&okTab);
    if (okGroup && okTab) {
        groupIndex = groupNumber - 1;
        tabIndex = tabNumber - 1;
        if (parts.size() == 3) {
            bool okSub = false;
            const int subNumber = parts.at(2).toInt(&okSub);
            if (!okSub) {
                return false;
            }
            subIndex = subNumber - 1;
        }
    } else {
        for (int g = 0; g < groups.size(); ++g) {
            if (groups.at(g).name.compare(parts.at(0), Qt::CaseInsensitive) != 0) {
                continue;
            }
            groupIndex = g;
            for (int t = 0; t < groups.at(g).tabs.size(); ++t) {
                if (groups.at(g).tabs.at(t).name.compare(parts.at(1), Qt::CaseInsensitive) == 0) {
                    tabIndex = t;
                    break;
                }
            }
            break;
        }
        if (parts.size() == 3 && groupIndex >= 0 && groupIndex < groups.size()
            && tabIndex >= 0 && tabIndex < groups.at(groupIndex).tabs.size()) {
            const auto &kcms = groups.at(groupIndex).tabs.at(tabIndex).kcms;
            for (int k = 0; k < kcms.size(); ++k) {
                if (kcms.at(k).label.compare(parts.at(2), Qt::CaseInsensitive) == 0) {
                    subIndex = k;
                    break;
                }
            }
            if (subIndex < 0) {
                return false;
            }
        }
    }
    if (groupIndex < 0 || groupIndex >= groups.size() || tabIndex < 0
        || tabIndex >= groups.at(groupIndex).tabs.size()) {
        return false;
    }
    if (parts.size() == 3 && (subIndex < 0 || subIndex >= groups.at(groupIndex).tabs.at(tabIndex).kcms.size())) {
        return false;
    }
    return true;
}

static bool hasFlag(int argc, char **argv, const char *flag)
{
    for (int i = 1; i < argc; ++i) {
        if (qstrcmp(argv[i], flag) == 0) {
            return true;
        }
    }
    return false;
}

int main(int argc, char **argv)
{
    // The window takes its colors from the KDE color scheme. Launched from a
    // bare shell (no QT_QPA_PLATFORMTHEME), Qt ignores kdeglobals and falls back
    // to the medium gray palette, so request the KDE platform theme when the
    // environment does not name one and the plugin is installed.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORMTHEME")) {
        const QString themePlugin = QLibraryInfo::path(QLibraryInfo::PluginsPath)
            + QStringLiteral("/platformthemes/KDEPlasmaPlatformTheme6.so");
        if (QFileInfo::exists(themePlugin)) {
            qputenv("QT_QPA_PLATFORMTHEME", "kde");
        }
    }

    // --list and --check only read plugin metadata, so they run without a
    // display; only the window needs QApplication.
    const bool headless = hasFlag(argc, argv, "--list") || hasFlag(argc, argv, "--check");
    std::unique_ptr<QCoreApplication> core;
    std::unique_ptr<QApplication> gui;
    if (headless) {
        core = std::make_unique<QCoreApplication>(argc, argv);
    } else {
        // Several KCMs (wallpaper, animations, screen) are QML and arrive as a
        // QQuickWidget. With the KDE platform theme active, Qt opens a second
        // OpenGL context for that widget; without context sharing the QML
        // surface never reaches the window and the page stays blank (the
        // module renders only into its own offscreen buffer). Must be set
        // before QApplication.
        QCoreApplication::setAttribute(Qt::AA_ShareOpenGLContexts);
        gui = std::make_unique<QApplication>(argc, argv);
    }
    QCoreApplication *app = core ? static_cast<QCoreApplication *>(core.get()) : static_cast<QCoreApplication *>(gui.get());
    QCoreApplication::setApplicationName(QStringLiteral("atena-settings"));
    if (gui) {
        QGuiApplication::setApplicationDisplayName(QStringLiteral("Configurações"));
        // Lets Wayland match the window to the installed .desktop file (icon,
        // taskbar grouping) instead of guessing from the binary name.
        QGuiApplication::setDesktopFileName(QStringLiteral("atena-settings"));
        // Every icon in this window is a theme name. Inside Plasma the theme
        // and the search paths come from the platform theme plugin; launched
        // from a bare shell (no QT_QPA_PLATFORMTHEME, empty XDG_DATA_DIRS) Qt
        // finds no theme and draws nothing, so both are filled in when missing.
        QStringList iconPaths = QIcon::themeSearchPaths();
        for (const QString &extra :
             {QStringLiteral("/usr/share/icons"), QStringLiteral("/usr/local/share/icons")}) {
            if (!iconPaths.contains(extra)) {
                iconPaths.append(extra);
            }
        }
        QIcon::setThemeSearchPaths(iconPaths);
        if (QIcon::themeName().isEmpty()) {
            QIcon::setThemeName(QStringLiteral("breeze"));
        }
        // Before any KCM is built: a module that redirects spawns
        // `systemsettings`, and it must find our shim, not the Plasma binary.
        preferOwnShim();
    }

    QCommandLineParser parser;
    parser.setApplicationDescription(
        QStringLiteral("Uma janela para as telas de configuração do Plasma, agrupadas por layout.conf."));
    parser.addHelpOption();
    QCommandLineOption layoutOption(QStringLiteral("layout"), QStringLiteral("arquivo de layout"), QStringLiteral("arquivo"));
    QCommandLineOption listOption(QStringLiteral("list"), QStringLiteral("mostra o layout e sai"));
    QCommandLineOption checkOption(QStringLiteral("check"), QStringLiteral("confere o layout contra a máquina e sai"));
    QCommandLineOption checkIconsOption(
        QStringLiteral("check-icons"),
        QStringLiteral("confere se o tema de ícones ativo tem todos os ícones do layout (precisa de display)"));
    QCommandLineOption selectOption(QStringLiteral("select"),
                                    QStringLiteral("monta um grupo/aba[/sub-aba] e sai (ex.: 2/3/1)"),
                                    QStringLiteral("grupo/aba[/sub-aba]"));
    QCommandLineOption measureOption(QStringLiteral("measure"),
                                     QStringLiteral("mede quanto tempo o grupo/aba[/sub-aba] leva para aparecer e carregar"),
                                     QStringLiteral("grupo/aba[/sub-aba]"));
    QCommandLineOption screenshotOption(
        QStringLiteral("screenshot"),
        QStringLiteral("salva a janela em PNG e sai (use com --select para escolher a aba)"), QStringLiteral("arquivo"));
    // Keeps the window open after --select; used to inspect a page by hand
    // (and to take a desktop screenshot) without an extra click.
    QCommandLineOption holdOption(QStringLiteral("hold"), QStringLiteral("não fecha depois de --select"));
    QCommandLineOption sizeOption(QStringLiteral("size"),
                                  QStringLiteral("tamanho da janela para --screenshot (ex.: 900x620)"),
                                  QStringLiteral("larguraxaltura"));
    // Preview an icon theme without touching the desktop settings. Handy to
    // compare candidates on the real window (--screenshot) before adopting one.
    QCommandLineOption iconsOption(QStringLiteral("icons"),
                                   QStringLiteral("usa outro tema de ícones só nesta janela (ex.: breeze-dark)"),
                                   QStringLiteral("tema"));
    parser.addOption(layoutOption);
    parser.addOption(listOption);
    parser.addOption(checkOption);
    parser.addOption(checkIconsOption);
    parser.addOption(selectOption);
    parser.addOption(holdOption);
    parser.addOption(measureOption);
    parser.addOption(screenshotOption);
    parser.addOption(sizeOption);
    parser.addOption(iconsOption);
    // Same shape as the Plasma binary: the KCM id as a bare argument. KCMs that
    // redirect launch `systemsettings <id>`, and the installed shim forwards
    // that here.
    parser.addPositionalArgument(QStringLiteral("kcm"),
                                 QStringLiteral("id de um KCM para já abrir nele (ex.: kcm_kwin_effects)"),
                                 QStringLiteral("[kcm]"));
    parser.process(*app);

    const QString layoutPath = parser.value(layoutOption).isEmpty() ? defaultLayoutPath() : parser.value(layoutOption);
    QVector<Group> groups;
    QString error;
    if (!parseLayout(layoutPath, groups, &error)) {
        std::fprintf(stderr, "%s\n", qPrintable(error));
        return 2;
    }

    const QHash<QString, KPluginMetaData> catalog = loadCatalog();

    if (parser.isSet(iconsOption)) {
        const QString theme = parser.value(iconsOption);
        const QStringList known = QIcon::themeSearchPaths();
        bool found = false;
        for (const QString &root : known) {
            if (QDir(root + QLatin1Char('/') + theme).exists()) {
                found = true;
                break;
            }
        }
        if (!found) {
            std::fprintf(stderr, "tema de ícones não encontrado: %s\n", qPrintable(theme));
            return 2;
        }
        QIcon::setThemeName(theme);
    }

    if (parser.isSet(listOption)) {
        std::printf("layout: %s\n\n", qPrintable(layoutPath));
        return printLayout(groups, catalog);
    }
    if (parser.isSet(checkOption)) {
        std::printf("layout: %s\n\n", qPrintable(layoutPath));
        return checkLayout(groups, catalog);
    }
    if (parser.isSet(checkIconsOption)) {
        std::printf("layout: %s\n\n", qPrintable(layoutPath));
        return checkIcons(groups, catalog);
    }

    const bool measure = parser.isSet(measureOption);
    const bool select = parser.isSet(selectOption);
    const bool screenshot = parser.isSet(screenshotOption);
    // "atena-settings kcm_x" opens the window on that page, the way the Plasma
    // binary does: it is how a KCM redirects to another screen (see
    // preferOwnShim) and how the installer's systemsettings shim forwards.
    const QString requested = parser.positionalArguments().value(0);
    const bool openWindow = !select && !measure && !requested.isEmpty();
    const QString target = select       ? parser.value(selectOption)
                           : measure    ? parser.value(measureOption)
                                        : requested;

    // Resolved before the window is built: a page missing from layout.conf has
    // to be added to the groups the window receives.
    int groupIndex = -1;
    int tabIndex = -1;
    int subIndex = -1;
    if (!target.isEmpty() && !resolveTarget(target, groups, groupIndex, tabIndex, subIndex)
        && !appendKcmPage(groups, catalog, target, groupIndex, tabIndex, subIndex)) {
        std::fprintf(stderr, "tela não encontrada: %s (use --list para ver o layout)\n", qPrintable(target));
        return 2;
    }

    SettingsWindow window(groups, catalog, select && !measure);
    QSize size(1100, 760);
    if (parser.isSet(sizeOption)) {
        const QStringList parts = parser.value(sizeOption).split(QLatin1Char('x'));
        bool okWidth = false;
        bool okHeight = false;
        if (parts.size() != 2) {
            std::fprintf(stderr, "tamanho inválido: %s (use LARGURAxALTURA)\n", qPrintable(parser.value(sizeOption)));
            return 2;
        }
        const int width = parts.at(0).toInt(&okWidth);
        const int height = parts.at(1).toInt(&okHeight);
        if (!okWidth || !okHeight || width < 400 || height < 300) {
            std::fprintf(stderr, "tamanho inválido: %s (mínimo 400x300)\n", qPrintable(parser.value(sizeOption)));
            return 2;
        }
        size = QSize(width, height);
    }
    window.resize(size);

    if (!target.isEmpty()) {
        if (screenshot) {
            window.select(groupIndex, tabIndex, subIndex);
            window.show();
            // A tab with nine KCMs takes seconds to fill; grabbing at a fixed
            // delay caught half-built pages. Wait for the load to finish (with a
            // cap so a broken KCM cannot hang the capture) and let the last
            // frames paint.
            QElapsedTimer settle;
            settle.start();
            while (window.isLoading() && settle.elapsed() < 30000) {
                QCoreApplication::processEvents(QEventLoop::AllEvents, 25);
            }
            settle.restart();
            while (settle.elapsed() < 300) {
                QCoreApplication::processEvents(QEventLoop::AllEvents, 25);
            }
            const QPixmap shot = window.grab();
            const QString file = parser.value(screenshotOption);
            if (!shot.save(file)) {
                std::fprintf(stderr, "não salvei %s\n", qPrintable(file));
                return 2;
            }
            std::printf("captura: %s (%dx%d)\n", qPrintable(file), shot.width(), shot.height());
            return window.hasError() ? 1 : 0;
        }

        if (!measure) {
            window.select(groupIndex, tabIndex, subIndex);
            // A positional KCM id is a real launch (a redirect or the shim), not
            // an inspection run: open the window and stay in it.
            if (openWindow) {
                window.show();
                return QApplication::exec();
            }
            const Tab &tab = groups.at(groupIndex).tabs.at(tabIndex);
            if (subIndex >= 0 && subIndex < tab.kcms.size()) {
                std::printf("montado: %s / %s / %s\n", qPrintable(groups.at(groupIndex).name),
                            qPrintable(tab.name), qPrintable(tab.kcms.at(subIndex).label));
            } else {
                std::printf("montado: %s / %s\n", qPrintable(groups.at(groupIndex).name),
                            qPrintable(tab.name));
            }
            if (parser.isSet(holdOption)) {
                window.show();
                return QApplication::exec();
            }
            return window.hasError() ? 1 : 0;
        }

        // Two numbers matter: how long the click blocks (skeleton only) and how
        // long until the visible screen is in place.
        QElapsedTimer timer;
        timer.start();
        if (!window.select(groupIndex, tabIndex, subIndex)) {
            std::fprintf(stderr, "não consegui abrir %s\n", qPrintable(target));
            return 2;
        }
        const qint64 visible = timer.elapsed();
        while (window.isLoading() && timer.elapsed() < 120000) {
            QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
        }
        std::printf("aba visível em %lld ms, tela carregada em %lld ms\n", visible, timer.elapsed());
        return window.hasError() ? 1 : 0;
    }

    window.show();
    return QApplication::exec();
}
