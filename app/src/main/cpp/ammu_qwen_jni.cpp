#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "AmmuQwen"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct NativeSession {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    llama_sampler *sampler = nullptr;
    std::atomic<bool> cancelled{false};
};

std::mutex lifecycle_mutex;

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ammu_assistant_QwenNativeEngine_nativeLoad(JNIEnv *env, jclass, jstring model_path, jint context_length) {
    const std::string path = jstring_to_string(env, model_path);
    if (path.empty() || context_length < 64) {
        throw_illegal_state(env, "Invalid Qwen model path or context length");
        return 0;
    }

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        throw_illegal_state(env, "llama.cpp could not load the bundled GGUF model");
        return 0;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_length);
    context_params.n_batch = 256;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    llama_context *context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        llama_model_free(model);
        throw_illegal_state(env, "llama.cpp could not allocate the model context");
        return 0;
    }

    auto *session = new (std::nothrow) NativeSession();
    if (session == nullptr) {
        llama_free(context);
        llama_model_free(model);
        throw_illegal_state(env, "Insufficient native memory for Ammu inference");
        return 0;
    }
    session->model = model;
    session->context = context;
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    session->sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(
        static_cast<uint32_t>(std::chrono::high_resolution_clock::now().time_since_epoch().count())));
    ALOGI("Loaded Qwen GGUF: %s, context=%d", path.c_str(), context_length);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ammu_assistant_QwenNativeEngine_nativeGenerate(JNIEnv *env, jclass, jlong handle, jstring prompt, jint max_tokens, jobject callback) {
    auto *session = reinterpret_cast<NativeSession *>(handle);
    if (session == nullptr || session->model == nullptr || session->context == nullptr) {
        throw_illegal_state(env, "Native Qwen session is not initialized");
        return nullptr;
    }

    session->cancelled.store(false);
    llama_memory_clear(llama_get_memory(session->context), true);
    llama_sampler_reset(session->sampler);
    const std::string user_text = jstring_to_string(env, prompt);
    const std::string text = user_text.find("<|im_start|>") == 0
        ? user_text
        : "<|im_start|>system\nYou are Ammu, a friendly and concise local AI assistant. Answer truthfully, helpfully, and in complete sentences.<|im_end|>\n"
          "<|im_start|>user\n" + user_text +
          "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    std::vector<llama_token> tokens(text.size() * 2 + 16);
    int32_t prompt_count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (prompt_count < 0) {
        tokens.resize(static_cast<size_t>(-prompt_count));
        const int32_t resized_count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        if (resized_count < 0) {
            throw_illegal_state(env, "Prompt could not be tokenized");
            return nullptr;
        }
        prompt_count = resized_count;
    }

    jclass callback_class = callback == nullptr ? nullptr : env->GetObjectClass(callback);
    jmethodID callback_method = callback_class == nullptr ? nullptr : env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");

    std::string output;
    int32_t n_prompt = prompt_count;
    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    if (llama_decode(session->context, batch) != 0) {
        throw_illegal_state(env, "llama.cpp could not decode the chat prompt");
        return nullptr;
    }

    llama_token bos = llama_vocab_bos(vocab);
    llama_token eos = llama_vocab_eos(vocab);
    for (int i = 0; i < max_tokens && !session->cancelled.load(); ++i) {
        llama_token next = llama_sampler_sample(session->sampler, session->context, batch.n_tokens - 1);
        llama_sampler_accept(session->sampler, next);
        if (llama_vocab_is_eog(vocab, next) || next == eos) break;

        std::vector<char> piece(128);
        int32_t piece_length = llama_token_to_piece(vocab, next, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (piece_length < 0) {
            piece.resize(static_cast<size_t>(-piece_length));
            piece_length = llama_token_to_piece(vocab, next, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        }
        if (piece_length > 0) {
            std::string decoded(piece.data(), static_cast<size_t>(piece_length));
            size_t stop = decoded.find("<|im_end|>");
            const char *other_stops[] = {"<|endoftext|>", "<|eot_id|>", "<|im_start|>"};
            for (const char *marker : other_stops) {
                size_t found = decoded.find(marker);
                if (found != std::string::npos && (stop == std::string::npos || found < stop)) stop = found;
            }
            if (stop != std::string::npos) {
                if (stop > 0) {
                    std::string prefix = decoded.substr(0, stop);
                    output += prefix;
                    if (callback != nullptr && callback_method != nullptr) {
                        jstring token = env->NewStringUTF(prefix.c_str());
                        env->CallVoidMethod(callback, callback_method, token);
                        env->DeleteLocalRef(token);
                    }
                }
                break;
            }
            output += decoded;
            if (callback != nullptr && callback_method != nullptr) {
                jstring token = env->NewStringUTF(decoded.c_str());
                env->CallVoidMethod(callback, callback_method, token);
                env->DeleteLocalRef(token);
            }
        }

        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(session->context, next_batch) != 0) {
            throw_illegal_state(env, "llama.cpp could not decode generated Qwen tokens");
            return nullptr;
        }
        batch = next_batch;
    }

    if (env->ExceptionCheck()) return nullptr;
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ammu_assistant_QwenNativeEngine_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<NativeSession *>(handle);
    if (session != nullptr) session->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ammu_assistant_QwenNativeEngine_nativeClose(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<NativeSession *>(handle);
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lock(lifecycle_mutex);
    if (session->context != nullptr) llama_free(session->context);
    if (session->sampler != nullptr) llama_sampler_free(session->sampler);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}
