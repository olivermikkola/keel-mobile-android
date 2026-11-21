#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"

namespace {

struct KeelSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> cached_tokens;
    std::atomic<bool> cancelled{false};
    std::mutex mutex;

    ~KeelSession() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::once_flag g_backend_init;

void silent_log_callback(enum ggml_log_level /*level*/, const char * /*text*/, void * /*user_data*/) {
    // Never log prompt fragments or model internals to logcat.
}

const std::unordered_set<std::string> kToolMarkupTokens = {
    "<tool_call>", "</tool_call>", "<function", "</function>",
    "<param", "</param>", "<parameters>", "</parameters>"
};

bool is_valid_utf8_complete(const std::string &s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t need = 0;
        if ((c & 0x80) == 0) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return false;
        if (i + need > n) return false;
        for (size_t j = 1; j < need; ++j) {
            if ((static_cast<unsigned char>(s[i + j]) & 0xC0) != 0x80) return false;
        }
        i += need;
    }
    return true;
}

std::string token_to_visible_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    const bool is_ctrl = llama_vocab_is_control(vocab, token);
    if (is_ctrl) {
        int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string s(buf, static_cast<size_t>(n));
            if (kToolMarkupTokens.count(s) > 0) return s;
        }
        return {};
    }
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string large(static_cast<size_t>(-n), '\0');
        int32_t n2 = llama_token_to_piece(vocab, token, &large[0], static_cast<int32_t>(large.size()), 0, false);
        return n2 > 0 ? large.substr(0, static_cast<size_t>(n2)) : std::string();
    }
    return n > 0 ? std::string(buf, static_cast<size_t>(n)) : std::string();
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::call_once(g_backend_init, []() {
        llama_log_set(silent_log_callback, nullptr);
        llama_backend_init();
    });
}

JNIEXPORT jstring JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject /*thiz*/) {
    std::call_once(g_backend_init, []() {
        llama_log_set(silent_log_callback, nullptr);
        llama_backend_init();
    });
    const char *info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "llama.cpp");
}

JNIEXPORT jlong JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeLoadModel(
    JNIEnv *env,
    jobject /*thiz*/,
    jstring jpath,
    jint contextSize,
    jint nThreads,
    jboolean quantizeKvQ4
) {
    std::call_once(g_backend_init, []() {
        llama_log_set(silent_log_callback, nullptr);
        llama_backend_init();
    });

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-safe across all Android SoCs; Vulkan backend can opt in later.

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(contextSize > 512 ? contextSize : 4096);
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    if (quantizeKvQ4) {
        cparams.type_k = GGML_TYPE_Q4_0;
        cparams.type_v = GGML_TYPE_Q4_0;
    } else {
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    auto *session = new KeelSession();
    session->model = model;
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<KeelSession *>(handle);
    delete session;
}

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeCancel(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<KeelSession *>(handle);
    session->cancelled.store(true, std::memory_order_release);
}

JNIEXPORT jlongArray JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeGenerate(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jstring jprompt,
    jint maxTokens,
    jboolean addSpecial,
    jfloat temp,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat repeatPenalty,
    jboolean greedy,
    jstring jgrammar,
    jobjectArray jtriggers,
    jobject callback
) {
    auto makeResult = [&](int64_t promptToks, int64_t reusedToks, int64_t genToks, int64_t prefillMs, int64_t decodeMs, int64_t status) -> jlongArray {
        jlongArray arr = env->NewLongArray(6);
        jlong vals[6] = {promptToks, reusedToks, genToks, prefillMs, decodeMs, status};
        env->SetLongArrayRegion(arr, 0, 6, vals);
        return arr;
    };

    if (handle == 0 || !jprompt) return makeResult(0, 0, 0, 0, 0, -1);
    auto *session = reinterpret_cast<KeelSession *>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    session->cancelled.store(false, std::memory_order_release);

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const char *promptChars = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptChars ? promptChars : "");
    if (promptChars) env->ReleaseStringUTFChars(jprompt, promptChars);

    // Tokenize prompt
    int32_t needed = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, addSpecial, true);
    if (needed <= 0) return makeResult(0, 0, 0, 0, 0, -2);
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (needed >= nCtx - 8) return makeResult(needed, 0, 0, 0, 0, -3); // prompt too long

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), needed, addSpecial, true);
    if (actual <= 0) return makeResult(0, 0, 0, 0, 0, -2);
    tokens.resize(static_cast<size_t>(actual));

    // Append-only prefix reuse: safe for hybrid SSM/attention architectures (Bonsai 2, LFM3).
    size_t reused = 0;
    if (!session->cached_tokens.empty() && session->cached_tokens.size() < tokens.size()) {
        bool exact_prefix = true;
        for (size_t i = 0; i < session->cached_tokens.size(); ++i) {
            if (session->cached_tokens[i] != tokens[i]) {
                exact_prefix = false;
                break;
            }
        }
        if (exact_prefix) {
            reused = session->cached_tokens.size();
        }
    }
    if (reused == 0) {
        llama_memory_clear(llama_get_memory(session->ctx), true);
        session->cached_tokens.clear();
    }

    // Prefill prompt tokens in chunks of n_batch
    const auto tPrefillStart = std::chrono::steady_clock::now();
    const int32_t batchSize = 256;
    llama_batch batch = llama_batch_init(batchSize, 0, 1);

    for (size_t pos = reused; pos < tokens.size(); ) {
        if (session->cancelled.load(std::memory_order_acquire)) {
            llama_batch_free(batch);
            return makeResult(actual, static_cast<int64_t>(reused), 0, 0, 0, 1);
        }
        const size_t chunk = std::min(static_cast<size_t>(batchSize), tokens.size() - pos);
        batch.n_tokens = static_cast<int32_t>(chunk);
        for (size_t i = 0; i < chunk; ++i) {
            batch.token[i] = tokens[pos + i];
            batch.pos[i] = static_cast<llama_pos>(pos + i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (pos + i == tokens.size() - 1) ? 1 : 0;
        }
        if (llama_decode(session->ctx, batch) != 0) {
            llama_batch_free(batch);
            llama_memory_clear(llama_get_memory(session->ctx), true);
            session->cached_tokens.clear();
            return makeResult(actual, static_cast<int64_t>(reused), 0, 0, 0, -4);
        }
        pos += chunk;
    }
    session->cached_tokens = tokens;
    const auto tPrefillEnd = std::chrono::steady_clock::now();
    const int64_t prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(tPrefillEnd - tPrefillStart).count();

    // Build sampler chain (optionally with lazy GBNF grammar)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);

    if (jgrammar != nullptr) {
        const char *gstr = env->GetStringUTFChars(jgrammar, nullptr);
        std::vector<std::string> triggerStrings;
        std::vector<const char *> triggerPtrs;
        if (jtriggers != nullptr) {
            jsize len = env->GetArrayLength(jtriggers);
            for (jsize i = 0; i < len; ++i) {
                auto js = static_cast<jstring>(env->GetObjectArrayElement(jtriggers, i));
                if (js) {
                    const char *cs = env->GetStringUTFChars(js, nullptr);
                    if (cs) {
                        triggerStrings.emplace_back(cs);
                        env->ReleaseStringUTFChars(js, cs);
                    }
                    env->DeleteLocalRef(js);
                }
            }
            for (const auto &s : triggerStrings) triggerPtrs.push_back(s.c_str());
        }
        llama_sampler *gs = nullptr;
        if (gstr && std::strlen(gstr) > 0) {
            if (!triggerPtrs.empty()) {
                gs = llama_sampler_init_grammar_lazy_patterns(
                    vocab, gstr, "root", triggerPtrs.data(), triggerPtrs.size(), nullptr, 0
                );
            } else {
                gs = llama_sampler_init_grammar(vocab, gstr, "root");
            }
        }
        if (gstr) env->ReleaseStringUTFChars(jgrammar, gstr);
        if (gs) {
            llama_sampler_chain_add(sampler, gs);
        }
    }

    if (greedy || temp <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f) llama_sampler_chain_add(sampler, llama_sampler_init_min_p(minP, 1));
        if (repeatPenalty > 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeatPenalty, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jmethodID onTokenMethod = nullptr;
    if (callback != nullptr) {
        jclass cbClass = env->GetObjectClass(callback);
        onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cbClass);
    }

    const auto tDecodeStart = std::chrono::steady_clock::now();
    int64_t generated = 0;
    int32_t curPos = static_cast<int32_t>(tokens.size());
    std::string utf8Buffer;

    try {
        for (int32_t step = 0; step < maxTokens && curPos < nCtx - 1; ++step) {
            if (session->cancelled.load(std::memory_order_acquire)) break;

            // Note: llama_sampler_sample() already calls llama_sampler_accept() internally.
            llama_token tok = llama_sampler_sample(sampler, session->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;

            std::string piece = token_to_visible_piece(vocab, tok);
            if (!piece.empty()) {
                utf8Buffer += piece;
                if (is_valid_utf8_complete(utf8Buffer)) {
                    if (callback && onTokenMethod) {
                        jstring jtok = env->NewStringUTF(utf8Buffer.c_str());
                        env->CallVoidMethod(callback, onTokenMethod, jtok);
                        env->DeleteLocalRef(jtok);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                            break;
                        }
                    }
                    utf8Buffer.clear();
                }
            }

            batch.n_tokens = 1;
            batch.token[0] = tok;
            batch.pos[0] = curPos;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;
            if (llama_decode(session->ctx, batch) != 0) break;

            session->cached_tokens.push_back(tok);
            curPos++;
            generated++;
        }
    } catch (...) {
        llama_sampler_free(sampler);
        llama_batch_free(batch);
        return makeResult(actual, static_cast<int64_t>(reused), generated, prefillMs, 0, -4);
    }

    const auto tDecodeEnd = std::chrono::steady_clock::now();
    const int64_t decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(tDecodeEnd - tDecodeStart).count();

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return makeResult(actual, static_cast<int64_t>(reused), generated, prefillMs, decodeMs, 0);
}

} // extern "C"
