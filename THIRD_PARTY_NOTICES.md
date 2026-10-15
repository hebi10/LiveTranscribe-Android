# Third-party notices / 제3자 라이선스

This project is a personal derivative of [LiveCaptionN](https://github.com/chartmann1590/LiveTranscribe-Android), distributed under the MIT License. The copyright notice remains in [LICENSE](LICENSE).

## Offline speech models

The build scripts bundle these speech models at APK build time:

- `vosk-model-small-en-us-0.15` — English (US), Apache License 2.0, from the official [Vosk model catalog](https://alphacephei.com/vosk/models). The official archive and all extracted model files are pinned by size and SHA-256 in `scripts/prepare_vosk_models.py`.
- [ReazonSpeech k2-v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2) — Japanese, Reazon Human Interaction Lab / reazon-research, Apache License 2.0. Pinned revision: `291488c8151be24d7da4bf7af26e533fad96e407`. The int8 encoder, decoder, joiner, and tokens are bundled with shortened filenames; weight contents are unmodified. File sizes and SHA-256 values are pinned in `scripts/prepare_japanese_asr.py`. License text: [`REAZON-APACHE-2.0.txt`](app/src/main/assets/model-licenses/REAZON-APACHE-2.0.txt).
- [Silero VAD](https://github.com/snakers4/silero-vad) — Copyright (c) 2020-present Silero Team, MIT License. Bundled from the official Sherpa-ONNX `asr-models` release, with size and SHA-256 pinned in `scripts/prepare_japanese_asr.py`. License text: [`SILERO-MIT.txt`](app/src/main/assets/model-licenses/SILERO-MIT.txt).

Preserve the relevant model attributions and license terms when redistributing a packaged APK. Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Other components

- [Vosk Android SDK](https://github.com/alphacep/vosk-api): consult the dependency license and transitive notices.
- [sherpa-onnx Android](https://github.com/k2-fsa/sherpa-onnx), pinned v1.13.8: Apache License 2.0; native runtime packaged from official AAR. The AAR SHA-256 is pinned in `scripts/prepare_language_id.py`.
- [Whisper Tiny](https://github.com/openai/whisper) multilingual int8 acoustic language identification model: upstream Whisper code/weights under MIT License, packaged at APK build time via the official Sherpa-ONNX release model archive. Encoder and decoder file sizes and SHA-256 values are pinned in `scripts/prepare_language_id.py`.
- [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation): subject to Google's ML Kit terms and the device's Google Play services. The SDK may collect diagnostics and usage metadata (including device/app information and configured language pair): https://developers.google.com/ml-kit/android-data-disclosure .
- AndroidX and Kotlin components retain their respective third-party licenses.

No separate remote transcription/translation API, AdMob, Firebase Analytics integration, error reporting, or payment SDK is part of the modified app. **ML Kit itself may nonetheless collect diagnostic and usage metadata.**
