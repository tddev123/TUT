#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <algorithm>
#include <chrono>
#include "whisper.h"

#define TAG "TutWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

using Clock = std::chrono::steady_clock;

// What the Kotlin side holds on to: the model plus the id of a job asked to stop early.
struct Handle {
    whisper_context *ctx;
    std::atomic<int> cancelJob{-1};
};

struct Run {
    Handle *handle;
    int jobId;
    Clock::time_point deadline;
    bool hasDeadline;
};

// Called by whisper.cpp during encoding and between decoder steps; returning true aborts.
// Stops runaway decodes (e.g. repetition loops on quiet or unclear audio) and jobs that
// were cancelled because their result is no longer wanted.
static bool shouldAbort(void *data) {
    auto *run = static_cast<Run *>(data);
    if (run->handle->cancelJob.load() == run->jobId) return true;
    return run->hasDeadline && Clock::now() >= run->deadline;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_tut_stt_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) LOGW("failed to load model %s", path);
    else LOGI("loaded model %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) return 0;
    return reinterpret_cast<jlong>(new Handle{ctx});
}

JNIEXPORT void JNICALL
Java_com_example_tut_stt_WhisperLib_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (!handle) return;
    whisper_free(handle->ctx);
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_example_tut_stt_WhisperLib_nativeCancel(JNIEnv *, jobject, jlong ptr, jint jobId) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (handle) handle->cancelJob.store(jobId);
}

JNIEXPORT jstring JNICALL
Java_com_example_tut_stt_WhisperLib_nativeTranscribe(JNIEnv *env, jobject, jlong ptr,
                                                     jfloatArray samples, jint threads,
                                                     jstring prompt, jint audioCtx,
                                                     jint timeoutMs, jint jobId) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (!handle) return env->NewStringUTF("");
    whisper_context *ctx = handle->ctx;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *promptChars = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(1, (int) threads);
    p.language = "en";
    p.translate = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.no_timestamps = true;
    p.no_context = true;
    p.single_segment = true;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.temperature_inc = 0.0f;   // no fallback re-decoding: keeps latency predictable
    p.initial_prompt = promptChars;
    if (audioCtx > 0) p.audio_ctx = std::min((int) audioCtx, whisper_model_n_audio_ctx(ctx));

    Run run{handle, (int) jobId, Clock::now() + std::chrono::milliseconds(timeoutMs), timeoutMs > 0};
    p.abort_callback = shouldAbort;
    p.abort_callback_user_data = &run;

    std::string text;
    if (whisper_full(ctx, p, data, n) == 0) {
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    } else if (handle->cancelJob.load() == run.jobId) {
        LOGI("job %d cancelled (%.1f s of audio)", run.jobId, n / 16000.0);
    } else if (run.hasDeadline && Clock::now() >= run.deadline) {
        LOGW("job %d gave up after %d ms on %.1f s of audio", run.jobId, (int) timeoutMs, n / 16000.0);
    } else {
        LOGW("whisper_full failed");
    }

    if (promptChars) env->ReleaseStringUTFChars(prompt, promptChars);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_tut_stt_WhisperLib_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
