// CP-123 (spec Phase 5): JNI bridge — in-process inference via llama.cpp.
// Kotlin side: com.aicodemax.ai.runtime.AicodeJni (ai/runtime module).
// Built in CI against pinned llama.cpp headers, linked with libllama.so.
// No shell, no subprocess, no localhost — everything runs in the app process.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> stop{false};
    std::string path;
    uint32_t n_ctx = 0;
};

thread_local std::string g_last_error;

void set_error(const std::string& msg) { g_last_error = msg; }

std::string j_to_utf(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Length of the longest valid-UTF8 prefix: token pieces can split mid-codepoint
// (common with Thai 3-byte chars) and NewStringUTF requires valid UTF-8.
size_t valid_utf8_prefix_len(const std::string& s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    size_t cont = 0;
    while (i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) {
        i--;
        cont++;
    }
    if (i == 0) return 0;  // all continuation bytes — wait for more
    const unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = 0;
    if ((lead & 0x80) == 0) need = 1;
    else if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    else return i - 1;  // invalid lead — drop it
    return (cont + 1 >= need) ? n : (i - 1);
}

bool ends_with_stop(const std::string& text, const std::vector<std::string>& stops) {
    for (const auto& s : stops) {
        if (!s.empty() && text.size() >= s.size() &&
            text.compare(text.size() - s.size(), s.size(), s) == 0) {
            return true;
        }
    }
    return false;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jstring jpath, jint ctx_size, jint threads) {
    g_last_error.clear();
    const std::string path = j_to_utf(env, jpath);
    if (path.empty()) {
        set_error("empty model path");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        set_error("llama_model_load_from_file failed: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size > 0 ? static_cast<uint32_t>(ctx_size) : 2048;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        set_error("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto* handle = new (std::nothrow) Handle();
    if (!handle) {
        set_error("out of memory");
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = llama_model_get_vocab(model);
    handle->path = path;
    handle->n_ctx = llama_n_ctx(ctx);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong h, jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jobjectArray jstops, jobject jsink) {
    g_last_error.clear();
    auto* handle = reinterpret_cast<Handle*>(h);
    if (!handle || !handle->ctx || !handle->vocab) {
        set_error("model not loaded");
        return env->NewStringUTF("");
    }
    const std::string prompt = j_to_utf(env, jprompt);
    if (prompt.empty()) {
        set_error("empty prompt");
        return env->NewStringUTF("");
    }

    std::vector<std::string> stops;
    if (jstops) {
        const jsize n = env->GetArrayLength(jstops);
        for (jsize i = 0; i < n; i++) {
            auto* s = static_cast<jstring>(env->GetObjectArrayElement(jstops, i));
            stops.push_back(j_to_utf(env, s));
            env->DeleteLocalRef(s);
        }
    }

    jmethodID on_token = nullptr;
    jclass sink_class = nullptr;
    if (jsink) {
        sink_class = env->GetObjectClass(jsink);
        on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
        if (!on_token) {
            set_error("sink has no onToken(String) method");
            return env->NewStringUTF("");
        }
    }

    handle->stop.store(false);
    llama_memory_clear(llama_get_memory(handle->ctx), true);

    // Tokenize prompt (parse_special so chat templates like <|im_start|> work).
    std::vector<llama_token> prompt_tokens(prompt.size() + 64);
    int32_t n_prompt = llama_tokenize(
        handle->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), true, true);
    if (n_prompt < 0) {
        // Buffer too small — retry with the required size.
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(
            handle->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), true, true);
    }
    if (n_prompt <= 0) {
        set_error("tokenize failed");
        return env->NewStringUTF("");
    }
    prompt_tokens.resize(n_prompt);

    // Decode prompt in batches.
    const int32_t n_batch = 512;
    int32_t n_past = 0;
    for (int32_t i = 0; i < n_prompt && !handle->stop.load();) {
        const int32_t n_eval = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        for (int32_t j = 0; j < n_eval; j++) {
            batch.token[j] = prompt_tokens[i + j];
            batch.pos[j] = n_past + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == n_prompt - 1) ? 1 : 0;
        }
        batch.n_tokens = n_eval;
        if (llama_decode(handle->ctx, batch) != 0) {
            llama_batch_free(batch);
            set_error("prompt decode failed");
            return env->NewStringUTF("");
        }
        llama_batch_free(batch);
        i += n_eval;
        n_past += n_eval;
    }

    // Sampler chain: top_k -> top_p -> temp -> dist.
    const uint32_t seed =
        static_cast<uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature >= 0 ? temperature : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));

    std::string out;
    out.reserve(4096);
    const int32_t n_predict = max_tokens > 0 ? max_tokens : 512;
    char piece[256];

    // Reused single-token batch with explicit positions (no reliance on the
    // context's automatic position tracker, which explicit-pos batches bypass).
    llama_batch step = llama_batch_init(1, 0, 1);
    step.n_tokens = 1;
    step.n_seq_id[0] = 1;
    step.seq_id[0][0] = 0;
    step.logits[0] = 1;

    size_t emitted = 0;  // bytes of `out` already sent to the sink
    for (int32_t i = 0; i < n_predict; i++) {
        if (handle->stop.load()) break;
        const llama_token id = llama_sampler_sample(smpl, handle->ctx, -1);
        if (llama_vocab_is_eog(handle->vocab, id)) break;
        const int32_t n_piece = llama_token_to_piece(
            handle->vocab, id, piece, sizeof(piece), 0, false);
        if (n_piece > 0) {
            out.append(piece, n_piece);
            if (on_token && jsink) {
                // Emit only the longest valid-UTF8 prefix not yet emitted.
                const size_t valid = valid_utf8_prefix_len(out);
                if (valid > emitted) {
                    jstring jpiece = env->NewStringUTF(out.substr(emitted, valid - emitted).c_str());
                    env->CallVoidMethod(jsink, on_token, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        break;
                    }
                    emitted = valid;
                }
            }
            if (ends_with_stop(out, stops)) {
                // Trim the stop sequence itself.
                for (const auto& s : stops) {
                    if (!s.empty() && out.size() >= s.size() &&
                        out.compare(out.size() - s.size(), s.size(), s) == 0) {
                        out.resize(out.size() - s.size());
                        break;
                    }
                }
                break;
            }
        }
        llama_sampler_accept(smpl, id);
        step.token[0] = id;
        step.pos[0] = n_past++;
        if (llama_decode(handle->ctx, step) != 0) {
            set_error("decode failed at step " + std::to_string(i));
            break;
        }
    }

    llama_batch_free(step);
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong h) {
    auto* handle = reinterpret_cast<Handle*>(h);
    if (handle) handle->stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeUnload(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong h) {
    auto* handle = reinterpret_cast<Handle*>(h);
    if (!handle) return;
    handle->stop.store(true);
    if (handle->ctx) llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeInfo(
    JNIEnv* env, jobject /*thiz*/, jlong h) {
    auto* handle = reinterpret_cast<Handle*>(h);
    if (!handle || !handle->ctx || !handle->model) {
        return env->NewStringUTF("not loaded");
    }
    const std::string info = "ctx=" + std::to_string(handle->n_ctx) +
        " vocab=" + std::to_string(llama_vocab_n_tokens(handle->vocab)) +
        " train_ctx=" + std::to_string(llama_model_n_ctx_train(handle->model)) +
        " path=" + handle->path;
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeVersion(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("aicode-jni/1.0 llama.cpp v0.4.1");
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_AicodeJni_nativeLastError(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
