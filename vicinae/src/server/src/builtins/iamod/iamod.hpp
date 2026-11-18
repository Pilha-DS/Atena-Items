#pragma once
#include <string_view>
#include "services/builtin-icon/builtin-icon.hpp"
#include "theme/colors.hpp"
#include "ui/image/url.hpp"

/**
 * IAMod owns the always available "Chat with AI" entry of the root search. Conversations run inline,
 * in the same window, using the models configured in the AI settings.
 */
namespace IAMod {

constexpr auto ROOT_ITEM_ID = std::string_view("iamod:ask");

inline const auto ICON = ImageURL::builtin(BuiltinIcon::Stars).setBackgroundTint(SemanticColor::Accent);

} // namespace IAMod
