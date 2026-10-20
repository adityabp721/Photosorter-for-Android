// JNI bridge to llama.cpp + mtmd (Qwen2.5-VL) for on-device photo classification.
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "VlmAi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_lctx = nullptr;
static mtmd_context * g_mctx = nullptr;
static llama_sampler * g_smpl = nullptr;

// Lock-free progress for the UI: stage 0 = idle, 1 = encoding image + prompt, 2 = writing answer.
static std::atomic<int> g_stage{0};
static std::atomic<int> g_tokens{0};
// Set by nativeAbort() (Stop button); checked by llama's abort callback and the token loop.
static std::atomic<bool> g_abort{false};

static bool abort_cb(void *) { return g_abort.load(); }

struct StageReset { ~StageReset() { g_stage = 0; } };

static void free_all() {
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_mctx) { mtmd_free(g_mctx); g_mctx = nullptr; }
    if (g_lctx) { llama_free(g_lctx); g_lctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_adityabalagere_photosorter_VlmAi_nativeInit(JNIEnv * env, jclass, jstring modelPath, jstring mmprojPath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();

    const char * mpath = env->GetStringUTFChars(modelPath, nullptr);
    const char * ppath = env->GetStringUTFChars(mmprojPath, nullptr);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only - GPU offload for GGUF on Android is not reliable
    g_model = llama_model_load_from_file(mpath, mparams);
    if (!g_model) {
        LOGE("failed to load model %s", mpath);
        env->ReleaseStringUTFChars(modelPath, mpath);
        env->ReleaseStringUTFChars(mmprojPath, ppath);
        return -1;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 4096;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    g_lctx = llama_init_from_model(g_model, cparams);
    if (!g_lctx) {
        LOGE("failed to create context");
        free_all();
        env->ReleaseStringUTFChars(modelPath, mpath);
        env->ReleaseStringUTFChars(mmprojPath, ppath);
        return -2;
    }
    llama_set_abort_callback(g_lctx, abort_cb, nullptr);

    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = false;
    mp.n_threads = nThreads > 0 ? nThreads : 4;
    mp.print_timings = false;
    mp.warmup = false;
    // keep image tokens modest for speed on phone-class hardware
    mp.image_min_tokens = 128;
    mp.image_max_tokens = 448;
    g_mctx = mtmd_init_from_file(ppath, g_model, mp);
    if (!g_mctx) LOGE("failed to load mmproj %s", ppath);
    env->ReleaseStringUTFChars(modelPath, mpath);
    env->ReleaseStringUTFChars(mmprojPath, ppath);
    if (!g_mctx) {
        free_all();
        return -3;
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g_smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist(42));

    LOGI("VLM ready: ctx=%d threads=%d", (int) cparams.n_ctx, nThreads);
    return 0;
}

// Classify one JPEG image with a free-form inner prompt. Returns the raw model text, or null on error.
extern "C" JNIEXPORT jstring JNICALL
Java_com_adityabalagere_photosorter_VlmAi_nativeClassify(JNIEnv * env, jclass, jbyteArray jpeg, jstring innerPrompt, jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_lctx || !g_mctx || !g_smpl) return nullptr;
    g_abort = false;
    g_tokens = 0;
    g_stage = 1;
    StageReset stage_reset;

    const char * prompt_c = env->GetStringUTFChars(innerPrompt, nullptr);
    jsize len = env->GetArrayLength(jpeg);
    std::vector<unsigned char> img((size_t) len);
    if (len > 0) env->GetByteArrayRegion(jpeg, 0, len, (jbyte *) img.data());

    std::string prompt = "<|im_start|>user\n<__media__>\n" + std::string(prompt_c) + "<|im_end|>\n<|im_start|>assistant\n";

    mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    auto bmp_w = mtmd_helper_bitmap_init_from_buf(g_mctx, img.data(), img.size(), false, opt);
    if (!bmp_w.bitmap) {
        LOGE("failed to decode image (%zu bytes)", img.size());
        env->ReleaseStringUTFChars(innerPrompt, prompt_c);
        return nullptr;
    }
    mtmd_bitmap * bitmaps[1] = { bmp_w.bitmap };

    mtmd_input_text in_txt { prompt.data(), (size_t) prompt.size(), true, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t rc = mtmd_tokenize(g_mctx, chunks, &in_txt, bitmaps, 1);
    if (rc != 0) {
        LOGE("tokenize failed: %d", rc);
        mtmd_bitmap_free(bmp_w.bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(innerPrompt, prompt_c);
        return nullptr;
    }

    // each photo is an independent conversation: drop the previous photo's KV cache + sampler state
    llama_memory_clear(llama_get_memory(g_lctx), true);
    llama_sampler_reset(g_smpl);

    llama_pos n_past = 0;
    rc = mtmd_helper_eval_chunks(g_mctx, g_lctx, chunks, 0, 0, 512, true, &n_past);
    if (rc != 0) {
        LOGE("eval failed: %d", rc);
        mtmd_bitmap_free(bmp_w.bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(innerPrompt, prompt_c);
        return nullptr;
    }

    g_stage = 2;
    std::string out;
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int nmax = maxTokens > 0 ? maxTokens : 60;
    for (int i = 0; i < nmax; i++) {
        if (g_abort) break;
        g_tokens = i + 1;
        llama_token tok = llama_sampler_sample(g_smpl, g_lctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[512];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, (size_t) n);
        llama_batch_ext * b = llama_batch_ext_init(g_lctx);
        int32_t bidx = llama_batch_ext_add_token(b, 0, tok);
        llama_pos pos = n_past++;
        llama_batch_ext_set_pos(b, bidx, &pos);
        llama_batch_ext_set_output_logits(b, bidx, true);
        if (llama_process(g_lctx, LLAMA_PROCESS_TYPE_DECODE, b) != 0) {
            LOGE("decode failed at token %d", i);
            llama_batch_ext_free(b);
            break;
        }
        llama_batch_ext_free(b);
    }

    mtmd_bitmap_free(bmp_w.bitmap);
    mtmd_input_chunks_free(chunks);
    env->ReleaseStringUTFChars(innerPrompt, prompt_c);

    if (out.empty() || g_abort) return nullptr;
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_adityabalagere_photosorter_VlmAi_nativeFree(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();
    llama_backend_free();
}

// stage * 1000 + tokens generated so far (lock-free, safe to poll while a classify runs)
extern "C" JNIEXPORT jint JNICALL
Java_com_adityabalagere_photosorter_VlmAi_nativeProgress(JNIEnv *, jclass) {
    int t = g_tokens.load();
    return g_stage.load() * 1000 + (t > 999 ? 999 : t);
}

// Asks the running classify (if any) to stop as soon as possible.
extern "C" JNIEXPORT void JNICALL
Java_com_adityabalagere_photosorter_VlmAi_nativeAbort(JNIEnv *, jclass) {
    g_abort = true;
}
