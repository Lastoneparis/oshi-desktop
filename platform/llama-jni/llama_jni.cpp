// =============================================================================
// libllama_jni — the native half of com.oshi.messenger.service.LlamaCpp
// PARITY.md row 2.5.
// =============================================================================
//
// WHAT THIS IS AND WHY IT IS HERE, NOT IN THE ANDROID TREE.
//
// The Kotlin declaration is SHARED SOURCE: `build.gradle.kts` compiles
// `OSHI-Android/app/src/main/java/com/oshi/messenger/service/LlamaCpp.kt` straight into
// this client. That file names three native methods on the class
// `com/oshi/messenger/service/LlamaCpp`, so the exported symbols below are fixed by it
// and by nothing else:
//
//     Java_com_oshi_messenger_service_LlamaCpp_loadModel     (Ljava/lang/String;)Z
//     Java_com_oshi_messenger_service_LlamaCpp_generate      (Ljava/lang/String;I)Ljava/lang/String;
//     Java_com_oshi_messenger_service_LlamaCpp_unloadModel   ()V
//
// If the Android app ships its own shim with the same signatures, this one and that one
// are interchangeable — which is the point of sharing the Kotlin rather than copying it.
// **This file has never been compiled by anyone yet**: it is built by the `native-llama`
// job in .github/workflows/desktop-ci.yml and by nothing on the machine it was written
// on. Treat every claim here as a claim until that job is green.
//
// THE ONE THING TO GET RIGHT: it must not take the JVM down.
//
// llama.cpp aborts the process on some failures (`GGML_ASSERT`), and a C++ exception that
// escapes a JNI method is undefined behaviour. Everything below is inside a try/catch that
// converts failure into `false` / `""` — which the Kotlin side then reports as
// ModelLoadFailed or EmptyOutput rather than as an answer. `GGML_ASSERT` is still fatal
// and cannot be caught from here; that is a real, stated limitation of running llama.cpp
// in-process, and the alternative (a subprocess) is a different design with a different
// cost. It is written down rather than papered over.
//
// The mutex mirrors the Kotlin side's serialisation. Both are needed: the Kotlin lock is
// what makes the REFUSAL honest ("busy"), and this one is what stops a second caller
// reaching ggml if some future caller forgets.

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

std::mutex g_mutex;
llama_model*   g_model = nullptr;
llama_context* g_ctx   = nullptr;

// Free everything. Caller holds g_mutex.
void unload_locked() {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_oshi_messenger_service_LlamaCpp_loadModel(JNIEnv* env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        unload_locked();

        const std::string path = jstring_to_utf8(env, jpath);
        if (path.empty()) return JNI_FALSE;

        llama_backend_init();

        llama_model_params mparams = llama_model_default_params();
        // No GPU offload. A desktop installer that assumes CUDA/Metal/Vulkan is an
        // installer that fails on most machines; CPU is the portable floor and the only
        // configuration CI can honestly claim to have built.
        mparams.n_gpu_layers = 0;

        g_model = llama_model_load_from_file(path.c_str(), mparams);
        if (g_model == nullptr) return JNI_FALSE;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 4096;
        cparams.n_batch = 512;

        g_ctx = llama_init_from_model(g_model, cparams);
        if (g_ctx == nullptr) { unload_locked(); return JNI_FALSE; }
        return JNI_TRUE;
    } catch (...) {
        unload_locked();
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_oshi_messenger_service_LlamaCpp_generate(JNIEnv* env, jobject, jstring jprompt, jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string result;
    try {
        if (g_ctx == nullptr || g_model == nullptr) return env->NewStringUTF("");

        const std::string prompt = jstring_to_utf8(env, jprompt);
        const llama_vocab* vocab = llama_model_get_vocab(g_model);

        // Tokenise. Two passes: the first with a negative return gives the count.
        int n_needed = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                       nullptr, 0, true, true);
        if (n_needed <= 0) return env->NewStringUTF("");
        std::vector<llama_token> tokens(n_needed);
        if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                           tokens.data(), n_needed, true, true) < 0) {
            return env->NewStringUTF("");
        }

        llama_memory_clear(llama_get_memory(g_ctx), true);

        llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
        if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("");

        llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        for (int i = 0; i < maxTokens; ++i) {
            llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;

            char piece[256];
            int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
            if (n > 0) result.append(piece, (size_t) n);

            llama_batch next = llama_batch_get_one(&tok, 1);
            if (llama_decode(g_ctx, next) != 0) break;
        }
        llama_sampler_free(smpl);
    } catch (...) {
        // Whatever happened, the JVM gets a String and the Kotlin side reports
        // EmptyOutput. An exception crossing this boundary is undefined behaviour.
        result.clear();
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_oshi_messenger_service_LlamaCpp_unloadModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        unload_locked();
        llama_backend_free();
    } catch (...) {
        // Nothing useful to report through a void method; the Kotlin side already treats
        // unload as best-effort.
    }
}

}  // extern "C"
