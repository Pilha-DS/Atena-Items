#include "theme-file.hpp"
#include "common/types.hpp"
#include <algorithm>
#include <expected>
#include "theme/theme.hpp"
#include "theme/colors.hpp"
#include "utils.hpp"
#include <filesystem>
#include <qcoreapplication.h>
#include <qfile.h>
#include <qfont.h>
#include <qguiapplication.h>
#include <qhash.h>
#include <qjsondocument.h>
#include <qjsonobject.h>
#include <qlogging.h>
#include <qobjectdefs.h>
#include <qpalette.h>
#include <qstandardpaths.h>

namespace fs = std::filesystem;

// clang-format on

namespace {

struct SystemColors {
  QColor background;
  QColor view;
  QColor foreground;
  QColor selection;
  QColor selectionForeground;
  QColor accent;
  QColor link;
  QColor negative;
  QColor positive;
  QColor neutral;
};

QColor mixColors(const QColor &a, const QColor &b, float t) {
  t = std::clamp(t, 0.0f, 1.0f);
  return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                          a.blueF() + (b.blueF() - a.blueF()) * t);
}

/**
 * Black or white, whichever reads better on top of `background`. Used for text
 * drawn over a color that comes from the desktop and whose brightness we cannot
 * assume, such as the accent.
 */
QColor contrastOn(const QColor &background) {
  return background.lightness() > 140 ? QColor(Qt::black) : QColor(Qt::white);
}

std::optional<QColor> parseColor(const QString &value) {
  const QString text = value.trimmed();
  if (text.isEmpty()) return std::nullopt;

  if (text.startsWith(QLatin1Char('#'))) {
    QColor const color(text);
    if (color.isValid()) return color;
    return std::nullopt;
  }

  const auto parts = text.split(QLatin1Char(','));
  if (parts.size() < 3) return std::nullopt;

  bool valid = false;
  int const r = parts[0].trimmed().toInt(&valid);
  if (!valid) return std::nullopt;
  int const g = parts[1].trimmed().toInt(&valid);
  if (!valid) return std::nullopt;
  int const b = parts[2].trimmed().toInt(&valid);
  if (!valid) return std::nullopt;
  if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return std::nullopt;

  return QColor(r, g, b);
}

std::optional<QColor> kdeColor(const QHash<QString, QString> &values, const QString &section,
                               const QString &key) {
  auto const it = values.find(section + QLatin1Char('/') + key);
  if (it == values.end()) return std::nullopt;
  return parseColor(*it);
}

/**
 * Colors of the active KDE color scheme. Only Linux reads kdeglobals: other
 * platforms get their palette from the native API through QPalette.
 */
std::optional<SystemColors> kdeSystemColors() {
#ifdef Q_OS_LINUX
  auto const path = kdeGlobalsPath();
  if (!path) return std::nullopt;

  QFile file(QString::fromStdString(path->string()));
  if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) return std::nullopt;

  QHash<QString, QString> values;
  QString section;
  while (!file.atEnd()) {
    const QString line = QString::fromUtf8(file.readLine()).trimmed();
    if (line.isEmpty() || line.startsWith(QLatin1Char('#'))) continue;
    if (line.startsWith(QLatin1Char('[')) && line.endsWith(QLatin1Char(']'))) {
      section = line.mid(1, line.size() - 2);
      continue;
    }
    auto const index = line.indexOf(QLatin1Char('='));
    if (index <= 0) continue;
    values.insert(section + QLatin1Char('/') + line.left(index).trimmed(), line.mid(index + 1).trimmed());
  }

  const auto background = kdeColor(values, "Colors:Window", "BackgroundNormal");
  const auto foreground = kdeColor(values, "Colors:Window", "ForegroundNormal");
  if (!background || !foreground) return std::nullopt;

  SystemColors colors{.background = *background, .foreground = *foreground};
  const bool isDark = colors.background.lightness() < 128;

  colors.view = kdeColor(values, "Colors:View", "BackgroundNormal").value_or(colors.background);
  colors.selection = kdeColor(values, "Colors:Selection", "BackgroundNormal").value_or(colors.background);
  colors.selectionForeground =
      kdeColor(values, "Colors:Selection", "ForegroundNormal").value_or(contrastOn(colors.selection));
  colors.accent = kdeColor(values, "General", "AccentColor").value_or(colors.selection);
  colors.link = kdeColor(values, "Colors:View", "ForegroundLink").value_or(colors.accent);
  colors.negative = kdeColor(values, "Colors:View", "ForegroundNegative")
                        .value_or(isDark ? QColor("#FF8A92") : QColor("#B3261E"));
  colors.positive = kdeColor(values, "Colors:View", "ForegroundPositive")
                        .value_or(isDark ? QColor("#6FD08C") : QColor("#1F7A45"));
  colors.neutral = kdeColor(values, "Colors:View", "ForegroundNeutral")
                       .value_or(isDark ? QColor("#F2A65A") : QColor("#A2621B"));
  return colors;
#else
  return std::nullopt;
#endif
}

/**
 * Colors reported by the platform itself. QPalette is the native system palette
 * on Windows, macOS and on Linux sessions that do not use KDE.
 */
SystemColors nativeSystemColors() {
  SystemColors colors{
      .background = QColor("#1e1f22"),
      .view = QColor("#1e1f22"),
      .foreground = QColor("#e6e6e6"),
  };

  if (auto *app = qobject_cast<QGuiApplication *>(QCoreApplication::instance())) {
    auto const palette = app->palette();
    colors.background = palette.color(QPalette::Window);
    colors.view = palette.color(QPalette::Base);
    colors.foreground = palette.color(QPalette::WindowText);
    colors.selection = palette.color(QPalette::Highlight);
    colors.selectionForeground = palette.color(QPalette::HighlightedText);
    colors.accent = palette.color(QPalette::Accent);
    colors.link = palette.color(QPalette::Link);
  }

  const bool isDark = colors.background.lightness() < 128;
  if (!colors.selection.isValid()) colors.selection = isDark ? QColor("#2B5F8A") : QColor("#3874C4");
  if (!colors.accent.isValid()) colors.accent = colors.selection;
  if (!colors.link.isValid()) colors.link = colors.accent;
  if (!colors.selectionForeground.isValid()) colors.selectionForeground = contrastOn(colors.selection);
  colors.negative = isDark ? QColor("#FF8A92") : QColor("#B3261E");
  colors.positive = isDark ? QColor("#6FD08C") : QColor("#1F7A45");
  colors.neutral = isDark ? QColor("#F2A65A") : QColor("#A2621B");
  return colors;
}

} // namespace

std::optional<std::filesystem::path> kdeGlobalsPath() {
#ifdef Q_OS_LINUX
  const QString configDir = QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation);
  if (configDir.isEmpty()) return std::nullopt;
  return fs::path(configDir.toStdString()) / "kdeglobals";
#else
  return std::nullopt;
#endif
}

void ThemeFile::setParent(const std::shared_ptr<ThemeFile> &file) { m_parent = file; }

const QString &ThemeFile::id() const { return m_data.id; }
const QString &ThemeFile::name() const { return m_data.name; }
const QString &ThemeFile::inherits() const { return m_data.inherits; }
ThemeVariant ThemeFile::variant() const { return m_data.variant; }
const QString &ThemeFile::description() const { return m_data.description; }
const ThemeFile::Icon &ThemeFile::icon() const { return m_data.icon; }
bool ThemeFile::isLight() const { return variant() == ThemeVariant::Light; }
bool ThemeFile::isDark() const { return variant() == ThemeVariant::Dark; }

QColor ThemeFile::resolve(const ColorLike &color) const {
  return std::visit(
      overloads{
          [](const QColor &c) { return c; },
          [](const QString &s) { return Utils::colorFromString(s); },
          [this](SemanticColor c) { return resolve(c); },
          [this](const DynamicColor &d) { return Utils::colorFromString(isLight() ? d.light : d.dark); },
      },
      color);
}

QColor ThemeFile::resolve(SemanticColor color) const {
  if (auto it = m_data.semantics.find(color); it != m_data.semantics.end()) {
    auto visitor = overloads{[](const QColor &color) { return color; },
                             [&](const ColorRef &ref) {
                               if (ref.color == color) { return inherit(ref.color); }
                               auto color = resolve(ref.color);
                               if (ref.opacity) {
                                 auto bg = resolve(SemanticColor::Background);
                                 float const a = std::clamp(static_cast<float>(*ref.opacity), 0.0f, 1.0f);
                                 color = QColor(qRound(color.red() * a + bg.red() * (1.0f - a)),
                                                qRound(color.green() * a + bg.green() * (1.0f - a)),
                                                qRound(color.blue() * a + bg.blue() * (1.0f - a)));
                               }
                               if (ref.darker) color = color.darker(std::max(0, *ref.darker + 100));
                               if (ref.lighter) color = color.lighter(std::max(0, *ref.lighter + 100));
                               return color;
                             }};

    auto color = std::visit(visitor, it->second);
    return color;
  }
  return deriveSemantic(color);
}

QString ThemeFile::resolveAsString(SemanticColor color) const { return Utils::rgbaFromColor(resolve(color)); }

ThemeFile ThemeFile::vicinaeDark() {
  InitData data;
  data.id = "vicinae-dark";
  data.name = "Vicinae Inkwell";
  data.variant = ThemeVariant::Dark;
  data.description = "Cool ink grounds, warm sand accents";
  data.semantics = {
      {SemanticColor::Background, QColor("#0f1014")},
      {SemanticColor::SecondaryBackground, QColor("#15161b")},
      {SemanticColor::ListItemSelectionBackground, QColor("#272831")},
      {SemanticColor::GridItemBackground, QColor("#1b1c22")},
      {SemanticColor::Foreground, QColor("#e7e5e4")},
      {SemanticColor::BackgroundBorder, QColor("#373842")},
      {SemanticColor::Accent, QColor("#b8944e")},
      {SemanticColor::AccentForeground, QColor("#0f1014")},
      {SemanticColor::LinkDefault, QColor("#c9a76e")},
      {SemanticColor::TextSelectionBackground, QColor("#264f78")},
      {SemanticColor::TextSelectionForeground, QColor("#e7e5e4")},

      {SemanticColor::Red, QColor("#B9543B")},
      {SemanticColor::Orange, QColor("#F0883E")},
      {SemanticColor::Yellow, QColor("#c9a76e")},
      {SemanticColor::Green, QColor("#3A9C61")},
      {SemanticColor::Cyan, QColor("#6a8a7c")},
      {SemanticColor::Blue, QColor("#2F6FED")},
      {SemanticColor::Magenta, QColor("#BC8CFF")},
      {SemanticColor::Purple, QColor("#BC8CFF")},
  };

  return ThemeFile(data);
}

ThemeFile ThemeFile::vicinaeLight() {
  InitData data;
  data.id = "vicinae-light";
  data.variant = ThemeVariant::Light;
  data.name = "Vicinae Sandstone";
  data.description = "Warm cream grounds, deepened sand accents";
  data.semantics = {
      {SemanticColor::Background, QColor("#faf8f4")},
      {SemanticColor::SecondaryBackground, QColor("#f0ece5")},
      {SemanticColor::ListItemSelectionBackground, QColor("#cac0aa")},
      {SemanticColor::Foreground, QColor("#1c1917")},
      {SemanticColor::BackgroundBorder, QColor("#82807a")},
      {SemanticColor::GridItemBackground, QColor("#e6e1d5")},
      {SemanticColor::Accent, QColor("#8a6d35")},
      {SemanticColor::AccentForeground, QColor("#faf8f4")},
      {SemanticColor::LinkDefault, QColor("#7a6132")},
      {SemanticColor::TextSelectionBackground, QColor("#add6ff")},
      {SemanticColor::TextSelectionForeground, QColor("#1c1917")},

      {SemanticColor::Red, QColor("#b9543b")},
      {SemanticColor::Orange, QColor("#c97a30")},
      {SemanticColor::Yellow, QColor("#9a7b3f")},
      {SemanticColor::Green, QColor("#2d7a4d")},
      {SemanticColor::Cyan, QColor("#44635a")},
      {SemanticColor::Blue, QColor("#1F6FEB")},
      {SemanticColor::Magenta, QColor("#8B6EBF")},
      {SemanticColor::Purple, QColor("#8B6EBF")},
  };

  return ThemeFile(data);
}

ThemeFile ThemeFile::system() {
  const SystemColors colors = kdeSystemColors().value_or(nativeSystemColors());
  const bool isDark = colors.background.lightness() < 128;

  // The scheme only defines surfaces, text and the selection color, so the
  // in-between tones are derived from them: a divider is a muted foreground and
  // a raised surface is the background nudged towards the text color.
  const QColor border = mix(colors.background, colors.foreground, 0.18f);
  const QColor raised = mix(colors.background, colors.foreground, 0.07f);
  const QColor purple = mix(colors.link, colors.negative, 0.55f);

  InitData data;
  data.id = SYSTEM_THEME_ID;
  data.name = QCoreApplication::translate("ThemeFile", "System");
  data.description = QCoreApplication::translate("ThemeFile", "Follows the desktop color scheme");
  data.variant = isDark ? ThemeVariant::Dark : ThemeVariant::Light;
  data.semantics = {
      {SemanticColor::Background, colors.background},
      {SemanticColor::SecondaryBackground, colors.view},
      {SemanticColor::Foreground, colors.foreground},
      {SemanticColor::BackgroundBorder, border},
      {SemanticColor::GridItemBackground, raised},
      {SemanticColor::ListItemSelectionBackground, colors.selection},
      {SemanticColor::ListItemSelectionForeground, colors.selectionForeground},
      {SemanticColor::Accent, colors.accent},
      {SemanticColor::AccentForeground, contrastOn(colors.accent)},
      {SemanticColor::LinkDefault, colors.link},
      {SemanticColor::TextSelectionBackground, colors.selection},
      {SemanticColor::TextSelectionForeground, colors.selectionForeground},

      {SemanticColor::Red, colors.negative},
      {SemanticColor::Orange, colors.neutral},
      {SemanticColor::Yellow, mix(colors.neutral, colors.foreground, 0.45f)},
      {SemanticColor::Green, colors.positive},
      {SemanticColor::Cyan, mix(colors.link, colors.positive, 0.5f)},
      {SemanticColor::Blue, colors.accent},
      {SemanticColor::Magenta, purple},
      {SemanticColor::Purple, purple},
  };

  return ThemeFile(data);
}

QColor ThemeFile::inherit(SemanticColor color) const {
  return m_parent ? m_parent->resolve(color) : QColor();
}

QColor ThemeFile::deriveSemantic(SemanticColor color) const {
  switch (color) {
  case SemanticColor::LinkDefault:
    return resolve(SemanticColor::Blue);
  case SemanticColor::LinkVisited:
    return resolve(SemanticColor::Purple);

  case SemanticColor::LoadingBar:
    return resolve(SemanticColor::TextMuted);
  case SemanticColor::DynamicToastSpinner:
    return resolve(SemanticColor::Foreground);

  case SemanticColor::ListItemSelectionForeground:
    return resolve(SemanticColor::Foreground);

  case SemanticColor::ListItemSecondarySelectionForeground:
    return resolve(SemanticColor::ListItemSelectionForeground);
  case SemanticColor::ListItemSecondarySelectionBackground:
    return resolve(SemanticColor::ListItemSelectionBackground);
  case SemanticColor::ListItemSecondaryHoverForeground:
    return resolve(SemanticColor::ListItemSecondarySelectionForeground);
  case SemanticColor::ListItemSecondaryHoverBackground:
    return isDark() ? resolve(SemanticColor::ListItemSecondarySelectionBackground).darker(110)
                    : mix(resolve(SemanticColor::ListItemSecondarySelectionBackground),
                          resolve(SemanticColor::Background), 0.25f);

  case ButtonPrimaryBackground:
    return withAlphaF(resolve(SemanticColor::ListItemSelectionBackground), 0.7);
  case ButtonPrimaryForeground:
    return resolve(SemanticColor::Foreground);
  case ButtonPrimaryHoverBackground:
    return withAlphaF(resolve(SemanticColor::ButtonPrimaryBackground), 0.7);
  case ButtonPrimaryHoverForeground:
    return resolve(SemanticColor::ButtonPrimaryBackground);
  case SemanticColor::ButtonPrimaryFocusOutline:
    return resolve(SemanticColor::InputBorderFocus);

  case SemanticColor::ScrollBarBackground:
    return withAlphaF(resolve(SemanticColor::Foreground), 0.25);
  case SemanticColor::SecondaryScrollBarBackground:
    return resolve(SemanticColor::ScrollBarBackground);

  case SemanticColor::PopoverBackground:
    return resolve(SemanticColor::Background);
  case SemanticColor::PopoverBorder:
    return resolve(SemanticColor::MainWindowBorder);

  case SemanticColor::TooltipBackground:
    return resolve(SemanticColor::PopoverBackground);
  case SemanticColor::TooltipBorder:
    return resolve(SemanticColor::PopoverBorder);
  case SemanticColor::TooltipForeground:
    return resolve(SemanticColor::Foreground);

  case SemanticColor::SecondaryBackgroundBorder:
    return resolve(SemanticColor::BackgroundBorder).lighter();
  case SemanticColor::ListItemSelectionBackground:
    return resolve(SemanticColor::SecondaryBackground);
  case SemanticColor::ListItemHoverBackground:
    return isDark() ? resolve(SemanticColor::ListItemSelectionBackground).darker(110)
                    : mix(resolve(SemanticColor::ListItemSelectionBackground),
                          resolve(SemanticColor::Background), 0.25f);
  case SemanticColor::ListItemHoverForegroud:
    return resolve(SemanticColor::Foreground);

  case SemanticColor::GridItemBackground:
    return resolve(SemanticColor::SecondaryBackground);
  case SemanticColor::GridItemSelectionOutline:
    return resolve(SemanticColor::Foreground);
  case SemanticColor::GridItemHoverOutline:
    return resolve(SemanticColor::GridItemSelectionOutline);

  case SemanticColor::AccentForeground:
    return QColor("#FFFFFF");

  case SemanticColor::TextSelectionBackground:
    return resolve(SemanticColor::Accent);
  case SemanticColor::TextSelectionForeground:
    return resolve(SemanticColor::AccentForeground);

  case SemanticColor::InputBackground:
    return resolve(SemanticColor::GridItemBackground);
  case SemanticColor::InputBorder:
    return resolve(SemanticColor::BackgroundBorder);
  case SemanticColor::InputBorderFocus:
    return mix(resolve(SemanticColor::InputBorder), resolve(SemanticColor::Foreground), 0.4f);
  case SemanticColor::InputBorderError:
    return resolve(SemanticColor::Red);
  case SemanticColor::TextPlaceholder:
    return withAlphaF(resolve(SemanticColor::TextPrimary), 0.6);

  case SemanticColor::Accent:
    return resolve(SemanticColor::Blue);

  case SemanticColor::TextPrimary:
    return resolve(SemanticColor::Foreground);
  case SemanticColor::TextMuted:
    return withAlphaF(resolve(SemanticColor::TextPrimary), 0.7);
  case SemanticColor::TextDanger:
    return resolve(SemanticColor::Red);
  case SemanticColor::TextSuccess:
    return resolve(SemanticColor::Green);

  case SemanticColor::MainWindowBorder: {
    auto color = resolve(SemanticColor::BackgroundBorder);
    return isLight() ? color.darker(110) : color.lighter(110);
  }
  case SemanticColor::MainWindowOuterBorder:
    return QColor::fromRgbF(0, 0, 0, isLight() ? 0.15f : 0.5f);
  case SemanticColor::SettingsWindowBorder:
    return resolve(SemanticColor::MainWindowBorder);
  case SemanticColor::StatusBarBackground:
    return resolve(SemanticColor::SecondaryBackground);

  case SemanticColor::ShortcutIndicatorBorder:
    return resolve(SemanticColor::BackgroundBorder);

  default:
    break;
  }

  return inherit(color);
}

QColor ThemeFile::withAlphaF(const QColor &color, float alpha) {
  QColor col = color;
  col.setAlphaF(alpha);
  return col;
}

QColor ThemeFile::mix(const QColor &a, const QColor &b, float t) {
  t = std::clamp(t, 0.0f, 1.0f);
  return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                          a.blueF() + (b.blueF() - a.blueF()) * t,
                          a.alphaF() + (b.alphaF() - a.alphaF()) * t);
}

std::optional<std::filesystem::path> ThemeFile::path() const { return m_data.path; }
