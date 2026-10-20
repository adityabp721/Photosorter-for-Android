// Host A/B harness: same chat template, prompts and image-token limits as the app's JNI bridge.
// Usage: ./compare-vlm <model.gguf> <mmproj.gguf> <list.tsv>
//   list.tsv lines: <image path>\t<sort|trip>\t<EXIF hints>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static const char * TRIP =
    "You are sorting a personal photo gallery into folders per trip/place (like japan-trip, swiss-trip). "
    "Look at the photo and its EXIF hints. If the GPS identifies a location, trust it over your visual guess.\n"
    "EXIF hints: %s\n"
    "If the photo is from a trip or travel to a place, the folder must be 1-3 word lowercase kebab-case "
    "using the country or main region plus -trip (Tokyo and Kyoto are both japan-trip). "
    "If it is NOT a travel photo, use exactly one of: food, animals, family, work, documents, screenshots, other.\n"
    "Reply with ONLY two lines:\n"
    "folder: <folder name>\n"
    "description: <one short sentence, max 12 words>";
static const char * SORT =
    "File this photo from a personal gallery. EXIF hints: %s\n"
    "Pick ONE broad lowercase folder for its main subject. Prefer exactly one of: food, people, "
    "pets, nature, travel, city, home, shopping, documents, screenshots, vehicles, events, art, other. "
    "Only invent a new 1-2 word kebab-case folder if none of these fits at all.\n"
    "Reply with ONLY two lines:\n"
    "folder: <folder name>\n"
    "description: <one short sentence, max 15 words>";

static const char * SMART =
    "Classify this photo from a personal phone gallery. EXIF hints: %s\n"
    "Answer with ONLY three lines:\n"
    "type: one of travel, food, people, pets, nature, city, home, shopping, documents, screenshot, vehicles, events, art, other\n"
    "place: the country where it was taken, ONLY if the photo clearly shows travel away from home "
    "(a famous landmark, foreign-language signs, a tourist site) or the GPS says so; otherwise none\n"
    "description: one short sentence, max 12 words";

static std::vector<unsigned char> read_file(const std::string & p) {
    std::ifstream f(p, std::ios::binary);
    return std::vector<unsigned char>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

static const char * LITE =
    "Describe this photo in one short sentence. (%s)";


int main(int argc, char ** argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s model mmproj list.tsv\n", argv[0]); return 1; }
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    auto mp0 = llama_model_default_params(); mp0.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(argv[1], mp0);
    auto cp = llama_context_default_params(); cp.n_ctx = 4096; cp.n_batch = 512; cp.n_threads = 6;
    llama_context * lctx = llama_init_from_model(model, cp);
    auto mp = mtmd_context_params_default();
    mp.use_gpu = false; mp.n_threads = 6; mp.print_timings = false; mp.warmup = false;
    mp.image_min_tokens = 128; mp.image_max_tokens = 448;
    mtmd_context * mctx = mtmd_init_from_file(argv[2], model, mp);
    if (!model || !lctx || !mctx) { fprintf(stderr, "load failed\n"); return 1; }
    auto sp = llama_sampler_chain_default_params(); sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    const llama_vocab * vocab = llama_model_get_vocab(model);

    std::ifstream list(argv[3]);
    std::string line;
    while (std::getline(list, line)) {
        std::istringstream ss(line);
        std::string path, kind, hints;
        std::getline(ss, path, '\t'); std::getline(ss, kind, '\t'); std::getline(ss, hints);
        if (path.empty()) continue;
        char inner[4096];
        snprintf(inner, sizeof inner, kind == "trip" ? TRIP : kind == "smart" ? SMART : kind == "lite" ? LITE : SORT, hints.c_str());
        std::string user = "<__media__>\n" + std::string(inner);
        llama_chat_message msg { "user", user.c_str() };
        std::vector<char> buf(user.size() + 1024);
        int32_t n = llama_chat_apply_template(llama_model_chat_template(model, nullptr), &msg, 1, true, buf.data(), (int32_t) buf.size());
        std::string prompt = n > 0 && (size_t) n <= buf.size() ? std::string(buf.data(), (size_t) n)
                                   : "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
        auto img = read_file(path);
        auto bw = mtmd_helper_bitmap_init_from_buf(mctx, img.data(), img.size(), false, mtmd_helper_init_opt_default());
        mtmd_bitmap * bm[1] = { bw.bitmap };
        mtmd_input_text t { prompt.data(), prompt.size(), true, true };
        mtmd_input_chunks * ch = mtmd_input_chunks_init();
        mtmd_tokenize(mctx, ch, &t, bm, 1);
        llama_memory_clear(llama_get_memory(lctx), true);
        llama_sampler_reset(smpl);
        auto t0 = std::chrono::steady_clock::now();
        llama_pos n_past = 0;
        mtmd_helper_eval_chunks(mctx, lctx, ch, 0, 0, 512, true, &n_past);
        auto t1 = std::chrono::steady_clock::now();
        std::string out;
        for (int i = 0; i < 60; i++) {
            llama_token tok = llama_sampler_sample(smpl, lctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            char buf[256]; int n = llama_token_to_piece(vocab, tok, buf, sizeof buf, 0, true);
            if (n > 0) out.append(buf, n);
            llama_batch_ext * b = llama_batch_ext_init(lctx);
            int32_t bi = llama_batch_ext_add_token(b, 0, tok);
            llama_pos pos = n_past++;
            llama_batch_ext_set_pos(b, bi, &pos);
            llama_batch_ext_set_output_logits(b, bi, true);
            llama_process(lctx, LLAMA_PROCESS_TYPE_DECODE, b);
            llama_batch_ext_free(b);
        }
        auto t2 = std::chrono::steady_clock::now();
        for (auto & c : out) if (c == '\n') c = '|';
        printf("%s\t%s\t%.1fs\t%.1fs\t%s\n", path.substr(path.find_last_of('/') + 1).c_str(), kind.c_str(),
               std::chrono::duration<double>(t1 - t0).count(), std::chrono::duration<double>(t2 - t1).count(), out.c_str());
        fflush(stdout);
        mtmd_bitmap_free(bw.bitmap);
        mtmd_input_chunks_free(ch);
    }
    return 0;
}
