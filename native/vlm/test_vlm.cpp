// Host test: load Qwen2.5-VL-3B + mmproj, classify one image, print the model's answer.
// Usage: ./test_vlm <model.gguf> <mmproj.gguf> <image.jpg> [max_tokens]
// Env VLM_PROMPT overrides the inner prompt text (for debugging).
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static std::vector<unsigned char> read_file(const char * path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(1); }
    return std::vector<unsigned char>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

int main(int argc, char ** argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s model.gguf mmproj.gguf image.jpg [max_tokens]\n", argv[0]); return 1; }
    const std::string model_path = argv[1];
    const std::string mmproj_path = argv[2];
    const int max_tokens = argc > 4 ? atoi(argv[4]) : 60;

    llama_backend_init();

    printf("loading text model...\n");
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) { fprintf(stderr, "failed to load model\n"); return 1; }

    printf("creating context...\n");
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch = 512;
    cparams.n_threads = 8;
    llama_context * lctx = llama_init_from_model(model, cparams);
    if (!lctx) { fprintf(stderr, "failed to create context\n"); return 1; }

    printf("loading vision projector...\n");
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = false;
    mp.n_threads = 8;
    mp.print_timings = true;
    mp.warmup = false;
    // keep image tokens modest for speed on phone-class hardware
    mp.image_min_tokens = 128;
    mp.image_max_tokens = 768;
    mtmd_context * mctx = mtmd_init_from_file(mmproj_path.c_str(), model, mp);
    if (!mctx) { fprintf(stderr, "failed to load mmproj\n"); return 1; }

    std::string inner;
    if (const char * custom = getenv("VLM_PROMPT")) {
        inner = custom;
    } else {
        inner =
            "You are sorting a personal photo gallery into folders per trip/place (like japan-trip, swiss-trip). "
            "Look at the photo and its EXIF hints. If the GPS identifies a location, trust it over your visual guess.\n"
            "EXIF hints: Taken 2024-05-14. GPS: 48.8584, 2.2945.\n"
            "Reply with ONLY two lines:\n"
            "folder: <1-3 word lowercase kebab-case folder name>\n"
            "description: <one short sentence, max 12 words>\n";
    }
    std::string prompt = "< |im_start|>user\n<__media__>\n" + inner + "< |im_end|>\n< |im_start|>assistant\n";

    printf("decoding image...\n");
    auto img = read_file(argv[3]);
    mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    auto bmp_w = mtmd_helper_bitmap_init_from_buf(mctx, img.data(), img.size(), false, opt);
    if (!bmp_w.bitmap) { fprintf(stderr, "failed to decode image\n"); return 1; }
    mtmd_bitmap * bitmaps[1] = { bmp_w.bitmap };

    mtmd_input_text in_txt { prompt.data(), (size_t) prompt.size(), true, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t rc = mtmd_tokenize(mctx, chunks, &in_txt, bitmaps, 1);
    if (rc != 0) { fprintf(stderr, "tokenize failed: %d\n", rc); return 1; }

    printf("prompt tokens: %zu\n", mtmd_helper_get_n_tokens(chunks));

    llama_pos n_past = 0;
    rc = mtmd_helper_eval_chunks(mctx, lctx, chunks, 0, 0, 512, true, &n_past);
    if (rc != 0) { fprintf(stderr, "eval failed: %d\n", rc); return 1; }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    printf("--- output ---\n");
    std::string out;
    const llama_vocab * vocab = llama_model_get_vocab(model);
    for (int i = 0; i < max_tokens; i++) {
        // sample from the last output of the previous evaluation
        llama_token tok = llama_sampler_sample(smpl, lctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[512];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, (size_t) n);
        // decode the sampled token at the next position
        llama_batch_ext * b = llama_batch_ext_init(lctx);
        int32_t bidx = llama_batch_ext_add_token(b, 0, tok);
        llama_pos pos = n_past++;
        llama_batch_ext_set_pos(b, bidx, &pos);
        llama_batch_ext_set_output_logits(b, bidx, true);
        if (llama_process(lctx, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "decode failed\n"); return 1; }
        llama_batch_ext_free(b);
    }
    printf("%s\n--- end ---\n", out.c_str());

    mtmd_bitmap_free(bmp_w.bitmap);
    mtmd_input_chunks_free(chunks);
    llama_sampler_free(smpl);
    mtmd_free(mctx);
    llama_free(lctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
