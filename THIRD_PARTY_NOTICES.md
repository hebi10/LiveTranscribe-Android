# Third-party notices / 제3자 라이선스

This project is a personal derivative of [LiveCaptionN](https://github.com/chartmann1590/LiveTranscribe-Android), distributed under the MIT License. Preserve the original copyright notice in [LICENSE](LICENSE).

## Current speech and translation components

| Component | Use and distribution | License and attribution |
| --- | --- | --- |
| [Qwen3-ASR 0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) | On-device speech recognition; int8 ONNX model files are downloaded only after explicit in-app setup. | Qwen Team; Apache License 2.0. [License](app/src/main/assets/licenses/Qwen3-ASR-LICENSE.txt) and [model/export provenance](app/src/main/assets/licenses/Qwen3-ASR-PROVENANCE.md). |
| [Hy-MT2 1.8B](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) | On-device Korean translation; Tencent's Q4_K_M GGUF is downloaded only after explicit in-app setup. | Tencent; Apache License 2.0. [License](app/src/main/assets/licenses/Hy-MT2-LICENSE.txt). |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | CPU translation runtime compiled into the APK, pinned at `d81235049384534c167caea52b85a694f6103d14`. | MIT License. [License](app/src/main/assets/licenses/llama.cpp-LICENSE.txt). |
| [sherpa-onnx 1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) | Android/native speech and language-identification runtime; official Android AAR bundled in the APK. | Apache License 2.0. [License](app/src/main/assets/licenses/sherpa-onnx-LICENSE.txt). |
| [Whisper Tiny](https://github.com/openai/whisper) | Multilingual int8 acoustic language identification; encoder and decoder bundled in the APK. | OpenAI; MIT License. [License](app/src/main/assets/licenses/Whisper-LICENSE.txt) and [support provenance](app/src/main/assets/licenses/support-PROVENANCE.md). |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Speech activity detector bundled in the APK from the official sherpa-onnx `asr-models` release. | Copyright (c) 2020-present Silero Team; MIT License. [License](app/src/main/assets/model-licenses/SILERO-MIT.txt). |

The Qwen ONNX/int8 export is pinned to `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` revision `68818b2313fe77bd06f6a7c5068ff3ef59d02b8a`. Its model card credits Wasser1462's Qwen3-ASR ONNX work; the accompanying provenance file distinguishes that conversion from the original Qwen model. The Hy-MT2 GGUF revision is `a0c709d9fac510f2c807aa3af52872340dc37a4a`. Exact downloaded file sizes and SHA-256 values are recorded in [OfflineModelCatalog.kt](app/src/main/java/com/charles/livecaptionn/models/OfflineModelCatalog.kt).

Whisper Tiny encoder/decoder pins are recorded in [prepare_language_id.py](scripts/prepare_language_id.py), independently matched to export revision `65176e2deb88badc814a94058666cadccc29b61c`. The Whisper source-license revision documents attribution; it is not claimed as the ONNX conversion's source revision. Silero pins are in [prepare_speech_support.py](scripts/prepare_speech_support.py), the AAR pin is in `prepare_language_id.py`, and the native source archive pin is in [prepare_native_translation.py](scripts/prepare_native_translation.py).

The packaged [NOTICE.txt](app/src/main/assets/model-licenses/NOTICE.txt) and `assets/licenses/` files retain attributions even though the large recognition and translation weights are delivered separately. Preserve these notices and applicable license terms when redistributing the app or model files. Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Other components and removed engines

AndroidX, Kotlin, coroutines, and their transitive components retain their respective licenses. Review the resolved dependency and native-runtime notices when redistributing a build.

**ML Kit Translation and Vosk SDK are absent from the current runtime dependencies.** Historical Vosk/ReazonSpeech preparation scripts and regression fixtures do not mean those models are included in the current APK. The current build rejects bundled legacy recognizer models, Qwen ONNX weights, and GGUF translation weights; it permits only the pinned Silero and Whisper support models.

The modified app has no remote transcription/translation API integration, AdMob, Firebase Analytics/Crashlytics, payment, or remote error-reporting SDK. Model preparation makes explicit HTTPS downloads; these providers can observe ordinary connection metadata. See [PRIVACY_AUDIT.md](PRIVACY_AUDIT.md) for the source audit's scope and limits.
