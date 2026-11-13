#pragma once
#include <string>
#include <string_view>
#include "services/ai/openai/openai-compatible-provider.hpp"

namespace AI {

/**
 * DeepSeek's cloud API follows the OpenAI chat completions contract, so only the endpoint and
 * the capabilities of its models need to be described here.
 */
class DeepSeekProvider : public OpenAICompatibleProvider {
public:
  explicit DeepSeekProvider(std::string id);

protected:
  Capabilities capabilitiesFor(std::string_view id) const override;
};

} // namespace AI
