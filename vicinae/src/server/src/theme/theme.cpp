#include "theme/theme.hpp"
#include "config/config.hpp"
#include "theme/colors.hpp"
#include "theme/theme-db.hpp"
#include "theme/theme-file.hpp"
#include <qevent.h>
#include <qguiapplication.h>
#include <qstylehints.h>

void ThemeService::setTheme(const ThemeFile &info) {
  m_theme = std::make_unique<ThemeFile>(info);
  emit themeChanged(info);
}

ThemeService &ThemeService::instance() {
  static ThemeService _instance;
  return _instance;
}

ThemeDatabase &ThemeService::db() { return *m_themeDb; }

ThemeDatabase::ThemeList ThemeService::themes() const { return m_themeDb->themes(); }

const ThemeFile *ThemeService::findTheme(const QString &id) { return m_themeDb->theme(id); }

double ThemeService::pointSize(TextSize size) const {
  switch (size) {
  case TextSize::TextRegular:
    return m_baseFontPointSize;
  case TextSize::TextTitle:
    return m_baseFontPointSize * 1.5;
  case TextSize::TextSmaller:
    return m_baseFontPointSize * 0.9;
  }

  return m_baseFontPointSize;
}

void ThemeService::setFontBasePointSize(double pointSize) { m_baseFontPointSize = pointSize; }

void ThemeService::reloadCurrentTheme() { setTheme(m_theme->id()); }

bool ThemeService::selectTheme(const QString &id, config::Manager &config) {
  m_themeDb->scan();

  const auto *theme = m_themeDb->theme(id);
  if (!theme) return false;

  if (theme->id() == config.value().systemTheme().name) {
    reloadCurrentTheme();
  } else {
    config.mergeThemeConfig({.name = theme->id().toStdString()});
  }

  return true;
}

bool ThemeService::setTheme(const QString &name) {
  // The system theme is generated from the desktop palette on demand, so it is
  // never served from the database copy, which can be a color scheme older than
  // the one the desktop is using right now.
  if (name == SYSTEM_THEME_ID) {
    setTheme(ThemeFile::system());
    return true;
  }

  if (auto theme = m_themeDb->theme(name)) {
    setTheme(*theme);
    return true;
  }
  return false;
}

const ThemeFile &ThemeService::theme() const { return *m_theme; }

ColorLike ThemeService::getTintColor(SemanticColor tint) const { return m_theme->resolve(tint); }

ThemeService::ThemeService() : m_themeDb(std::make_shared<ThemeDatabase>()) {
  m_themeDb->scan();
  setTheme(SYSTEM_THEME_ID);
  connect(m_themeDb.get(), &ThemeDatabase::themeChanged, this, [this](const ThemeFile &theme) {
    if (m_theme->id() == theme.id()) { setTheme(theme); }
  });
  if (auto *app = qobject_cast<QGuiApplication *>(QCoreApplication::instance())) {
    app->installEventFilter(this);
    connect(app->styleHints(), &QStyleHints::colorSchemeChanged, this, [this]() {
      if (m_theme->id() == SYSTEM_THEME_ID) setTheme(ThemeFile::system());
    });
  }
}

bool ThemeService::eventFilter(QObject *watched, QEvent *event) {
  // A palette change with the same light/dark variant (a new accent, say) does
  // not change the color scheme, so it has to be picked up from the event.
  if (event->type() == QEvent::ApplicationPaletteChange && m_theme->id() == SYSTEM_THEME_ID) {
    setTheme(ThemeFile::system());
  }
  return QObject::eventFilter(watched, event);
}
