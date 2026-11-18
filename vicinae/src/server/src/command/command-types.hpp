#pragma once
#include <QString>
#include <cstdint>
#include <glaze/glaze.hpp>
#include <optional>
#include <qjsonobject.h>
#include <utility>
#include <vector>
#include "ui/quick/attachment-content.hpp"

class CommandContext;

using LaunchContext = glz::generic;

/**
 * Who asked for a command to run. Launcher means the user is interacting with the launcher, the others
 * usually happen while they are doing something else.
 */
enum class LaunchSource : std::uint8_t { Launcher, Shortcut, External };

struct LaunchProps {
  QString query;
  std::vector<std::pair<QString, QString>> arguments;
  std::optional<LaunchContext> launchContext;
  std::optional<QString> fallbackText;
  std::optional<QString> cwd;
  // Files picked in the launcher search bar; they belong to the command that is being launched.
  std::vector<AttachmentContent> attachments;
  LaunchSource source = LaunchSource::Launcher;
};

enum CommandMode : std::uint8_t {
  CommandModeInvalid,
  CommandModeView,
  CommandModeNoView,
  CommandModeMenuBar
};
enum CommandType : std::uint8_t { CommandTypeBuiltin, CommandTypeExtension };
