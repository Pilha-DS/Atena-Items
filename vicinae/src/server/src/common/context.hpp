#pragma once
#include <memory>
#include "common/types.hpp"

class NavigationController;
class CommandController;
class ServiceRegistry;
class OverlayController;
class SettingsController;
class ChatWindowController;
class AttachmentModel;

/**
 * Global object that can be used to access global vicinae state (services, navigation utils...)
 */
struct ApplicationContext : NonCopyable {
  std::unique_ptr<NavigationController> navigation;
  std::unique_ptr<OverlayController> overlay;
  ServiceRegistry *services;
  std::unique_ptr<SettingsController> settings;
  std::unique_ptr<ChatWindowController> chat;
  // Attachments picked in the launcher search bar, owned by the launcher window. Whichever
  // entrypoint starts a chat from the root search takes them, so an image pasted into the query
  // reaches the conversation whether the user presses Tab or activates the "Chat with AI" row.
  AttachmentModel *searchAttachments = nullptr;
};
