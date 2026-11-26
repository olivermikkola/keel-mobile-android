#include <jni.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

struct KeelSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *vision = nullptr;
    bool using_gpu = false;
    std::vector<llama_token> cached_tokens;
    std::atomic<bool> cancelled{false};
    std::mutex mutex;

    ~KeelSession() {
        if (vision) mtmd_free(vision);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

struct KeelEmbedSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int32_t n_embd = 0;
    std::mutex mutex;

    ~KeelEmbedSession() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::once_flag g_backend_init;

void silent_log_callback(enum ggml_log_level /*level*/, const char * /*text*/, void * /*user_data*/) {
    // Never log prompt fragments or model internals to logcat.
}

void ensure_backend_initialized() {
    std::call_once(g_backend_init, []() {
        llama_log_set(silent_log_callback, nullptr);
        mtmd_helper_log_set(silent_log_callback, nullptr);
        llama_backend_init();
    });
}

bool has_vulkan_gpu_device() {
    ensure_backend_initialized();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev && ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            return true;
        }
    }
    return false;
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

llama_sampler *build_sampler_chain(
    JNIEnv *env,
    const llama_vocab *vocab,
    jfloat temp,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat repeatPenalty,
    jboolean greedy,
    jstring jgrammar,
    jobjectArray jtriggers
) {
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
        if (gs) llama_sampler_chain_add(sampler, gs);
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
    return sampler;
}

int64_t run_decode_loop(
    JNIEnv *env,
    KeelSession *session,
    const llama_vocab *vocab,
    llama_sampler *sampler,
    llama_batch &batch,
    int32_t startPos,
    int32_t maxTokens,
    int32_t nCtx,
    bool recordTokensToCache,
    jobject callback,
    int64_t &decodeMsOut
) {
    jmethodID onTokenMethod = nullptr;
    if (callback != nullptr) {
        jclass cbClass = env->GetObjectClass(callback);
        onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cbClass);
    }

    const auto tDecodeStart = std::chrono::steady_clock::now();
    int64_t generated = 0;
    int32_t curPos = startPos;
    std::string utf8Buffer;

    for (int32_t step = 0; step < maxTokens && curPos < nCtx - 1; ++step) {
        if (session->cancelled.load(std::memory_order_acquire)) break;

        // llama_sampler_sample() already invokes llama_sampler_accept() internally.
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
        if (llama_decode(session->ctx, batch) != 0) {
            llama_memory_clear(llama_get_memory(session->ctx), true);
            session->cached_tokens.clear();
            break;
        }

        if (recordTokensToCache) session->cached_tokens.push_back(tok);
        curPos++;
        generated++;
    }

    if (session->cancelled.load(std::memory_order_acquire)) {
        llama_memory_clear(llama_get_memory(session->ctx), true);
        session->cached_tokens.clear();
    }

    const auto tDecodeEnd = std::chrono::steady_clock::now();
    decodeMsOut = std::chrono::duration_cast<std::chrono::milliseconds>(tDecodeEnd - tDecodeStart).count();
    return generated;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    ensure_backend_initialized();
}

JNIEXPORT jboolean JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeHasGpuBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    return has_vulkan_gpu_device() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject /*thiz*/) {
    ensure_backend_initialized();
    const char *info = llama_print_system_info();
    std::string out(info ? info : "llama.cpp");
    if (has_vulkan_gpu_device()) out += " | GPU: Vulkan";
    else out += " | GPU: CPU-only";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeLoadModel(
    JNIEnv *env,
    jobject /*thiz*/,
    jstring jpath,
    jstring jprojectorPath,
    jint contextSize,
    jint nThreads,
    jboolean quantizeKvQ4,
    jboolean useGpu
) {
    ensure_backend_initialized();

    const char *pathChars = env->GetStringUTFChars(jpath, nullptr);
    if (!pathChars) return 0;
    std::string path(pathChars);
    env->ReleaseStringUTFChars(jpath, pathChars);

    std::string projectorPath;
    if (jprojectorPath != nullptr) {
        const char *pChars = env->GetStringUTFChars(jprojectorPath, nullptr);
        if (pChars) {
            projectorPath = pChars;
            env->ReleaseStringUTFChars(jprojectorPath, pChars);
        }
    }

    const bool tryGpu = useGpu && has_vulkan_gpu_device();
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    bool usingGpu = false;

    auto tryLoad = [&](bool gpu) -> bool {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = gpu ? 99 : 0;
        model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) return false;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(contextSize > 512 ? contextSize : 4096);
        cparams.n_batch = 256;
        cparams.n_ubatch = 256;
        cparams.n_threads = nThreads > 0 ? nThreads : 4;
        cparams.n_threads_batch = cparams.n_threads;
        if (!gpu) {
            cparams.offload_kqv = false;
            cparams.op_offload = false;
        }
        if (quantizeKvQ4) {
            cparams.type_k = GGML_TYPE_Q4_0;
            cparams.type_v = GGML_TYPE_Q4_0;
        } else {
            cparams.type_k = GGML_TYPE_F16;
            cparams.type_v = GGML_TYPE_F16;
        }
        ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            llama_model_free(model);
            model = nullptr;
            return false;
        }
        usingGpu = gpu;
        return true;
    };

    // Try Vulkan GPU first when requested; automatically fall back to CPU if driver refuses.
    bool loaded = false;
    if (tryGpu) loaded = tryLoad(true);
    if (!loaded) loaded = tryLoad(false);
    if (!loaded) return 0;

    auto *session = new KeelSession();
    session->model = model;
    session->ctx = ctx;
    session->using_gpu = usingGpu;

    if (!projectorPath.empty()) {
        mtmd_context_params vparams = mtmd_context_params_default();
        vparams.use_gpu = usingGpu;
        vparams.n_threads = nThreads > 0 ? nThreads : 4;
        vparams.warmup = false;
        mtmd_context *vctx = mtmd_init_from_file(projectorPath.c_str(), model, vparams);
        if (!vctx || !mtmd_support_vision(vctx)) {
            if (vctx) mtmd_free(vctx);
            delete session;
            return 0;
        }
        session->vision = vctx;
    }

    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeIsSessionGpu(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    auto *session = reinterpret_cast<KeelSession *>(handle);
    return session->using_gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    delete reinterpret_cast<KeelSession *>(handle);
}

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeCancel(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    reinterpret_cast<KeelSession *>(handle)->cancelled.store(true, std::memory_order_release);
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

    int32_t needed = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, addSpecial, true);
    if (needed <= 0) return makeResult(0, 0, 0, 0, 0, -2);
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (needed >= nCtx - 8) return makeResult(needed, 0, 0, 0, 0, -3);

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), needed, addSpecial, true);
    if (actual <= 0) return makeResult(0, 0, 0, 0, 0, -2);
    tokens.resize(static_cast<size_t>(actual));

    size_t reused = 0;
    if (!session->cached_tokens.empty() && session->cached_tokens.size() < tokens.size()) {
        bool exact_prefix = true;
        for (size_t i = 0; i < session->cached_tokens.size(); ++i) {
            if (session->cached_tokens[i] != tokens[i]) {
                exact_prefix = false;
                break;
            }
        }
        if (exact_prefix) reused = session->cached_tokens.size();
    }
    if (reused == 0) {
        llama_memory_clear(llama_get_memory(session->ctx), true);
        session->cached_tokens.clear();
    }

    const auto tPrefillStart = std::chrono::steady_clock::now();
    const int32_t batchSize = 256;
    llama_batch batch = llama_batch_init(batchSize, 0, 1);

    for (size_t pos = reused; pos < tokens.size(); ) {
        if (session->cancelled.load(std::memory_order_acquire)) {
            llama_batch_free(batch);
            llama_memory_clear(llama_get_memory(session->ctx), true);
            session->cached_tokens.clear();
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

    llama_sampler *sampler = build_sampler_chain(env, vocab, temp, topK, topP, minP, repeatPenalty, greedy, jgrammar, jtriggers);
    int64_t decodeMs = 0;
    int64_t generated = 0;
    try {
        generated = run_decode_loop(env, session, vocab, sampler, batch, static_cast<int32_t>(tokens.size()), maxTokens, nCtx, true, callback, decodeMs);
    } catch (...) {
        llama_memory_clear(llama_get_memory(session->ctx), true);
        session->cached_tokens.clear();
        llama_sampler_free(sampler);
        llama_batch_free(batch);
        return makeResult(actual, static_cast<int64_t>(reused), generated, prefillMs, decodeMs, -4);
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return makeResult(actual, static_cast<int64_t>(reused), generated, prefillMs, decodeMs, 0);
}

JNIEXPORT jlongArray JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeGenerateVision(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jstring jprompt,
    jintArray jwidths,
    jintArray jheights,
    jobjectArray jrgbArrays,
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
    auto makeResult = [&](int64_t promptToks, int64_t genToks, int64_t prefillMs, int64_t decodeMs, int64_t status) -> jlongArray {
        jlongArray arr = env->NewLongArray(6);
        jlong vals[6] = {promptToks, 0, genToks, prefillMs, decodeMs, status};
        env->SetLongArrayRegion(arr, 0, 6, vals);
        return arr;
    };

    if (handle == 0 || !jprompt || !jwidths || !jheights || !jrgbArrays) return makeResult(0, 0, 0, 0, -1);
    auto *session = reinterpret_cast<KeelSession *>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->vision) return makeResult(0, 0, 0, 0, -5);
    session->cancelled.store(false, std::memory_order_release);

    const char *promptChars = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptChars ? promptChars : "");
    if (promptChars) env->ReleaseStringUTFChars(jprompt, promptChars);

    const jsize count = env->GetArrayLength(jrgbArrays);
    if (count <= 0 || env->GetArrayLength(jwidths) != count || env->GetArrayLength(jheights) != count) {
        return makeResult(0, 0, 0, 0, -2);
    }

    jint *widths = env->GetIntArrayElements(jwidths, nullptr);
    jint *heights = env->GetIntArrayElements(jheights, nullptr);
    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto jbytes = static_cast<jbyteArray>(env->GetObjectArrayElement(jrgbArrays, i));
        if (!jbytes) continue;
        jbyte *raw = env->GetByteArrayElements(jbytes, nullptr);
        const jsize len = env->GetArrayLength(jbytes);
        const uint32_t w = static_cast<uint32_t>(widths[i]);
        const uint32_t h = static_cast<uint32_t>(heights[i]);
        if (raw && static_cast<size_t>(len) >= static_cast<size_t>(w) * h * 3) {
            mtmd_bitmap *bm = mtmd_bitmap_init(w, h, reinterpret_cast<const unsigned char *>(raw));
            if (bm) bitmaps.push_back(bm);
        }
        if (raw) env->ReleaseByteArrayElements(jbytes, raw, JNI_ABORT);
        env->DeleteLocalRef(jbytes);
    }
    env->ReleaseIntArrayElements(jwidths, widths, JNI_ABORT);
    env->ReleaseIntArrayElements(jheights, heights, JNI_ABORT);

    auto cleanupBitmaps = [&]() {
        for (auto *bm : bitmaps) mtmd_bitmap_free(bm);
        bitmaps.clear();
    };

    if (static_cast<jsize>(bitmaps.size()) != count) {
        cleanupBitmaps();
        return makeResult(0, 0, 0, 0, -5);
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->cached_tokens.clear();

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text inputText;
    inputText.text = prompt.c_str();
    inputText.add_special = addSpecial;
    inputText.parse_special = true;

    const auto tPrefillStart = std::chrono::steady_clock::now();
    int32_t tokRes = mtmd_tokenize(session->vision, chunks, &inputText, bitmaps.data(), bitmaps.size());
    cleanupBitmaps();
    if (tokRes != 0) {
        mtmd_input_chunks_free(chunks);
        return makeResult(0, 0, 0, 0, -5);
    }

    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    const size_t nTokens = mtmd_helper_get_n_tokens(chunks);
    if (static_cast<int32_t>(nTokens) >= nCtx - 8) {
        mtmd_input_chunks_free(chunks);
        return makeResult(static_cast<int64_t>(nTokens), 0, 0, 0, -3);
    }

    llama_pos newPast = 0;
    int32_t evalRes = mtmd_helper_eval_chunks(session->vision, session->ctx, chunks, 0, 0, 256, true, &newPast);
    mtmd_input_chunks_free(chunks);
    if (evalRes != 0) {
        return makeResult(static_cast<int64_t>(nTokens), 0, 0, 0, -4);
    }
    const auto tPrefillEnd = std::chrono::steady_clock::now();
    const int64_t prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(tPrefillEnd - tPrefillStart).count();

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_batch batch = llama_batch_init(1, 0, 1);
    llama_sampler *sampler = build_sampler_chain(env, vocab, temp, topK, topP, minP, repeatPenalty, greedy, jgrammar, jtriggers);
    int64_t decodeMs = 0;
    int64_t generated = 0;
    try {
        generated = run_decode_loop(env, session, vocab, sampler, batch, static_cast<int32_t>(newPast), maxTokens, nCtx, false, callback, decodeMs);
    } catch (...) {
        llama_sampler_free(sampler);
        llama_batch_free(batch);
        return makeResult(static_cast<int64_t>(nTokens), generated, prefillMs, decodeMs, -4);
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return makeResult(static_cast<int64_t>(nTokens), generated, prefillMs, decodeMs, 0);
}

// =========================================================================
// Native Embeddings (Search by meaning / Recall with EmbeddingGemma 300M)
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeLoadEmbeddingModel(
    JNIEnv *env,
    jobject /*thiz*/,
    jstring jpath,
    jint nThreads
) {
    ensure_backend_initialized();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // EmbeddingGemma 300M runs fast on CPU without displacing chat GPU memory
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    auto *sess = new KeelEmbedSession();
    sess->model = model;
    sess->ctx = ctx;
    sess->n_embd = llama_model_n_embd(model);
    return reinterpret_cast<jlong>(sess);
}

JNIEXPORT void JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeUnloadEmbeddingModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    delete reinterpret_cast<KeelEmbedSession *>(handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_keelmobile_android_inference_LlamaNative_nativeEmbed(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jstring jtext
) {
    if (handle == 0 || !jtext) return nullptr;
    auto *sess = reinterpret_cast<KeelEmbedSession *>(handle);
    std::lock_guard<std::mutex> lock(sess->mutex);

    const char *textChars = env->GetStringUTFChars(jtext, nullptr);
    std::string text(textChars ? textChars : "");
    if (textChars) env->ReleaseStringUTFChars(jtext, textChars);
    if (text.empty()) return nullptr;

    const llama_vocab *vocab = llama_model_get_vocab(sess->model);
    int32_t needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, false);
    if (needed <= 0) return nullptr;
    const int32_t maxToks = std::min(needed, 510);
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t actual = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), needed, true, false);
    if (actual <= 0) return nullptr;
    if (actual > maxToks) actual = maxToks;

    llama_memory_clear(llama_get_memory(sess->ctx), true);
    llama_batch batch = llama_batch_init(actual, 0, 1);
    batch.n_tokens = actual;
    for (int32_t i = 0; i < actual; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }

    int32_t rc = llama_decode(sess->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) return nullptr;

    const float *emb = llama_get_embeddings_seq(sess->ctx, 0);
    if (!emb) emb = llama_get_embeddings_ith(sess->ctx, -1);
    if (!emb || sess->n_embd <= 0) return nullptr;

    std::vector<float> vec(static_cast<size_t>(sess->n_embd));
    double normSq = 0.0;
    for (int32_t i = 0; i < sess->n_embd; ++i) {
        vec[static_cast<size_t>(i)] = emb[i];
        normSq += static_cast<double>(emb[i]) * static_cast<double>(emb[i]);
    }
    const float norm = normSq > 0.0 ? static_cast<float>(std::sqrt(normSq)) : 1.0f;
    for (float &v : vec) v /= norm;

    jfloatArray out = env->NewFloatArray(sess->n_embd);
    env->SetFloatArrayRegion(out, 0, sess->n_embd, vec.data());
    return out;
}

} // extern "C"
