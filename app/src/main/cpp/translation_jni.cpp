#include "translation_engine.h"
#include <jni.h>
#include <exception>
#include <string>

namespace {
void fail(JNIEnv * env, const char * message, const char * type = "java/lang/IllegalStateException") {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_charles_livecaptionn_translation_NativeTranslationBridge_create(
    JNIEnv * env, jobject, jstring path, jint threads, jint context_tokens) {
    if (!path) { fail(env, "Translation model path is missing"); return 0; }
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string model_path(chars);
    env->ReleaseStringUTFChars(path, chars);
    try { return livecaption::create_translation(model_path, threads, context_tokens); }
    catch (const std::exception & error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_charles_livecaptionn_translation_NativeTranslationBridge_translate(
    JNIEnv * env, jobject, jlong handle, jlong request_id, jbyteArray prompt,
    jint max_tokens, jint timeout_ms) {
    if (!prompt) { fail(env, "Translation input is missing"); return nullptr; }
    jsize size = env->GetArrayLength(prompt);
    if (size < 1 || size > 16384) { fail(env, "Translation input limit exceeded"); return nullptr; }
    std::string content(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(prompt, 0, size, reinterpret_cast<jbyte *>(content.data()));
    if (env->ExceptionCheck()) return nullptr;
    try {
        auto result = livecaption::translate(handle, request_id, content, max_tokens, timeout_ms);
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(result.size()));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const livecaption::TranslationTimeoutError & error) {
        fail(env, error.what(), "java/util/concurrent/TimeoutException");
        return nullptr;
    } catch (const std::exception & error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_charles_livecaptionn_translation_NativeTranslationBridge_cancel(
    JNIEnv *, jobject, jlong handle, jlong request_id) {
    livecaption::cancel_translation(handle, request_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_charles_livecaptionn_translation_NativeTranslationBridge_destroy(
    JNIEnv *, jobject, jlong handle) {
    livecaption::destroy_translation(handle);
}
