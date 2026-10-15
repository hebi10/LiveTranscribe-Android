#include "translation_engine.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace livecaption {
namespace {
using Clock = std::chrono::steady_clock;
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::mutex execution;
    std::atomic<int64_t> cancelled_through{0};
    std::atomic<bool> closed{false};
    int64_t active_request = 0;
    Clock::time_point deadline;
    ~Session() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::mutex sessions_mutex;
std::unordered_map<int64_t, std::shared_ptr<Session>> sessions;
std::atomic<int64_t> next_handle{1};
std::once_flag initialized;

void quiet_log(ggml_log_level, const char *, void *) {}

std::shared_ptr<Session> session_for(int64_t handle) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto found = sessions.find(handle);
    if (found == sessions.end()) throw std::runtime_error("Translation session unavailable");
    return found->second;
}

bool aborted(void * value) {
    auto * session = static_cast<Session *>(value);
    return session->closed.load() || session->cancelled_through.load() >= session->active_request ||
           Clock::now() >= session->deadline;
}

void require_active(Session & session) {
    if (session.closed.load() || session.cancelled_through.load() >= session.active_request)
        throw std::runtime_error("Translation cancelled");
    if (Clock::now() >= session.deadline) throw std::runtime_error("Translation deadline exceeded");
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool special) {
    int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, special);
    if (count == std::numeric_limits<int32_t>::min()) throw std::runtime_error("Translation input too long");
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(0, -count)));
    count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), false, special);
    if (count < 0) throw std::runtime_error("Translation tokenization failed");
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::vector<llama_token> prompt_tokens(const llama_vocab * vocab, const std::string & prompt) {
    auto result = tokenize(vocab, "<｜hy_begin▁of▁sentence｜><｜hy_User｜>", true);
    auto content = tokenize(vocab, prompt, false);
    auto assistant = tokenize(vocab, "<｜hy_Assistant｜>", true);
    result.insert(result.end(), content.begin(), content.end());
    result.insert(result.end(), assistant.begin(), assistant.end());
    return result;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, false);
    }
    if (count < 0) throw std::runtime_error("Translation decoding failed");
    return {buffer.data(), static_cast<size_t>(count)};
}
}

int64_t create_translation(const std::string & model_path, int threads, int context_tokens) {
    if (threads < 1 || threads > 4 || context_tokens < 256 || context_tokens > 2048)
        throw std::invalid_argument("Unsupported translation configuration");
    std::call_once(initialized, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
    auto session = std::make_shared<Session>();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    session->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!session->model) throw std::runtime_error("Translation model could not be loaded");
    char architecture[64] = {};
    llama_model_meta_val_str(session->model, "general.architecture", architecture, sizeof(architecture));
    if (std::string(architecture) != "hunyuan-dense") throw std::runtime_error("Unexpected translation model");
    auto params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_tokens);
    params.n_batch = 128;
    params.n_ubatch = 128;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.no_perf = true;
    params.offload_kqv = false;
    params.op_offload = false;
    params.abort_callback = aborted;
    params.abort_callback_data = session.get();
    session->context = llama_init_from_model(session->model, params);
    if (!session->context) throw std::runtime_error("Translation context could not be created");
    int64_t handle = next_handle.fetch_add(1);
    std::lock_guard<std::mutex> lock(sessions_mutex);
    sessions.emplace(handle, std::move(session));
    return handle;
}

std::string translate(int64_t handle, int64_t request_id, const std::string & prompt,
                      int max_tokens, int timeout_ms) {
    if (request_id < 1 || prompt.empty() || prompt.size() > 16384 || max_tokens < 1 || max_tokens > 256 ||
        timeout_ms < 1 || timeout_ms > 30000) throw std::invalid_argument("Unsupported translation request");
    auto session = session_for(handle);
    std::lock_guard<std::mutex> lock(session->execution);
    session->active_request = request_id;
    session->deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
    require_active(*session);
    const auto * vocab = llama_model_get_vocab(session->model);
    auto tokens = prompt_tokens(vocab, prompt);
    if (tokens.size() + static_cast<size_t>(max_tokens) > llama_n_ctx(session->context))
        throw std::runtime_error("Translation context limit exceeded");
    llama_memory_clear(llama_get_memory(session->context), true);
    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(sampler_params), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0, 0));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(0));
    for (size_t offset = 0; offset < tokens.size(); offset += 128) {
        require_active(*session);
        int count = static_cast<int>(std::min<size_t>(128, tokens.size() - offset));
        if (llama_decode(session->context, llama_batch_get_one(tokens.data() + offset, count)) != 0) {
            require_active(*session);
            throw std::runtime_error("Translation prompt evaluation failed");
        }
    }
    std::string result;
    for (int generated = 0; generated < max_tokens; ++generated) {
        require_active(*session);
        llama_token token = llama_sampler_sample(sampler.get(), session->context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            if (result.empty()) throw std::runtime_error("Translation produced no text");
            return result;
        }
        result += token_piece(vocab, token);
        if (result.size() > 8192) throw std::runtime_error("Translation output limit exceeded");
        if (llama_decode(session->context, llama_batch_get_one(&token, 1)) != 0) {
            require_active(*session);
            throw std::runtime_error("Translation generation failed");
        }
    }
    throw std::runtime_error("Translation output limit exceeded");
}

void cancel_translation(int64_t handle, int64_t through_request) {
    std::shared_ptr<Session> session;
    try { session = session_for(handle); } catch (const std::runtime_error &) { return; }
    int64_t current = session->cancelled_through.load();
    while (current < through_request && !session->cancelled_through.compare_exchange_weak(current, through_request)) {}
}

void destroy_translation(int64_t handle) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(sessions_mutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        session = found->second;
        session->closed.store(true);
        sessions.erase(found);
    }
    // An in-flight call keeps its shared ownership until decoding has stopped.
}
}
