#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>

namespace livecaption {
class TranslationTimeoutError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

int64_t create_translation(const std::string & model_path, int threads, int context_tokens);
std::string translate(int64_t handle, int64_t request_id, const std::string & prompt,
                      int max_tokens, int timeout_ms);
void cancel_translation(int64_t handle, int64_t through_request);
void destroy_translation(int64_t handle);
}
