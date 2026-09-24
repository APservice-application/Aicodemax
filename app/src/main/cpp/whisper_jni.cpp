// CP-140 (file STT): JNI bridge — on-device speech-to-text via whisper.cpp.
// Kotlin side: com.aicodemax.ai.runtime.WhisperJni (ai/runtime module).
// Built in CI against pinned whisper.cpp headers, statically linked.
// No shell, no subprocess, no network — everything runs in the app process.
//
// Input is 16 kHz mono float PCM (decoded + resampled on the Kotlin side).
// Output is a JSON string: {"segments":[{"t0":ms,"t1":ms,"text":"..."}]}.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct Handle {
    whisper_context* ctx = nullptr;
    std::mutex mu;
    int threads = 4;
    std::string path;
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

void json_escape(std::string& out, const char* s) {
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(s); *p; ++p) {
        switch (*p) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", *p);
                    out += buf;
                } else {
                    out += static_cast<char>(*p);
                }
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aicodemax_ai_runtime_WhisperJni_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jstring jpath, jint threads) {
    g_last_error.clear();
    const std::string path = j_to_utf(env, jpath);
    if (path.empty()) {
        set_error("empty model path");
        return 0;
    }
    try {
        whisper_context_params cparams = whisper_context_default_params();
        whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
        if (!ctx) {
            set_error("whisper_init failed: " + path);
            return 0;
        }
        auto* h = new Handle();
        h->ctx = ctx;
        h->threads = threads > 0 ? threads : 4;
        h->path = path;
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        set_error(e.what());
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_WhisperJni_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray jsamples,
    jstring jlang, jboolean jtranslate) {
    g_last_error.clear();
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h || !h->ctx) {
        set_error("whisper not loaded");
        return nullptr;
    }
    if (!jsamples) {
        set_error("no samples");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(jsamples);
    if (n <= 0) {
        set_error("empty samples");
        return nullptr;
    }
    if (n > 60 * 60 * 16000) {  // sanity cap: 60 min at 16 kHz
        set_error("audio too long (max 60 min)");
        return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jsamples, 0, n, pcm.data());
    const std::string lang = j_to_utf(env, jlang);

    std::lock_guard<std::mutex> lock(h->mu);
    try {
        whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wparams.n_threads = h->threads;
        wparams.print_progress = false;
        wparams.print_realtime = false;
        wparams.print_timestamps = false;
        wparams.translate = (jtranslate == JNI_TRUE);
        std::string lang_store = lang;
        if (lang_store.empty() || lang_store == "auto") {
            wparams.detect_language = true;
        } else {
            wparams.language = lang_store.c_str();
        }
        if (whisper_full(h->ctx, wparams, pcm.data(), static_cast<int>(pcm.size())) != 0) {
            set_error("whisper_full failed");
            return nullptr;
        }
        std::string json = "{\"segments\":[";
        const int nseg = whisper_full_n_segments(h->ctx);
        for (int i = 0; i < nseg; ++i) {
            // t0/t1 are centiseconds → ms.
            const int64_t t0 = whisper_full_get_segment_t0(h->ctx, i);
            const int64_t t1 = whisper_full_get_segment_t1(h->ctx, i);
            const char* text = whisper_full_get_segment_text(h->ctx, i);
            if (i > 0) json += ',';
            json += "{\"t0\":";
            json += std::to_string(t0 * 10);
            json += ",\"t1\":";
            json += std::to_string(t1 * 10);
            json += ",\"text\":\"";
            json_escape(json, text ? text : "");
            json += "\"}";
        }
        json += "]}";
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        set_error(e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_aicodemax_ai_runtime_WhisperJni_nativeUnload(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mu);
        if (h->ctx) whisper_free(h->ctx);
    }
    delete h;
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_WhisperJni_nativeVersion(
    JNIEnv* env, jobject /*thiz*/) {
    // Pinned in CI (.github/workflows/android.yml).
    return env->NewStringUTF("whisper-jni/1.0 (whisper.cpp v1.9.4)");
}

JNIEXPORT jstring JNICALL
Java_com_aicodemax_ai_runtime_WhisperJni_nativeLastError(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
