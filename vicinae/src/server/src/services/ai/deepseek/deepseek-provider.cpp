#include "deepseek-provider.hpp"
#include <utility>

namespace AI {

DeepSeekProvider::DeepSeekProvider(std::string id)
    : OpenAICompatibleProvider(std::move(id), "deepseek", "https://api.deepseek.com/v1") {}

Capabilities DeepSeekProvider::capabilitiesFor(std::string_view id) const {
  // DeepSeek only serves chat models: no transcription, embeddings or image generation.
  if (id.contains("embed") || id.contains("whisper") || id.contains("tts")) return 0;

  // Reasoning models spend part of their answer budget thinking and do not take tool calls.
  if (id.contains("reasoner")) return Capability::Completion | Capability::Thinking;

  // Only the flash models read images; the rest of the lineup is text only, and sending an image
  // to them is rejected instead of silently dropped, which is why the flag has to be right here.
  auto caps = Capability::Completion | Capability::ToolCalling;
  if (id.contains("flash")) caps |= Capability::Vision;

  return caps;
}

} // namespace AI
