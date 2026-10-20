# Third-party notices

| Component | Use in PhotoSorter | License |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (git submodule `native/llama.cpp`) | On-device model inference, compiled into the app | MIT |
| [Qwen3-VL-2B-Instruct GGUF](https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF) | On-device vision-language model, downloaded by the app at runtime (not in this repository) | Apache 2.0 |
| [SmolVLM2-500M-Video-Instruct GGUF](https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF) (Hugging Face) | Lite on-device model for phones with less RAM, downloaded at runtime (not in this repository) | Apache 2.0 |
| [MediaPipe Tasks Vision](https://github.com/google-ai-edge/mediapipe) | Fallback image labeler | Apache 2.0 |
| EfficientNet-Lite0 image classifier (`app/src/main/assets/labeler.tflite`, from the MediaPipe model zoo) | Fallback image labeler model | Apache 2.0 |
| [Material Icons](https://github.com/google/material-design-icons) | App logo artwork and in-app icons | Apache 2.0 |
| Jetpack Compose, AndroidX libraries | User interface and Android integration | Apache 2.0 |
| [OkHttp](https://github.com/square/okhttp) | HTTPS client (Gemini API, model download) | Apache 2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Background work | Apache 2.0 |
| JUnit 4, Mockito (tests only) | Unit tests | EPL 1.0, MIT |

Google Gemini (optional cloud mode) is a remote service used with the user's own API key under
Google's terms; no Gemini code is included here.
