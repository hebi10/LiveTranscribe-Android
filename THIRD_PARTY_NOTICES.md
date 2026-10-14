# Third-party notices / 제3자 라이선스

This project is a personal derivative of [LiveCaptionN](https://github.com/chartmann1590/LiveTranscribe-Android), distributed under the MIT License. The copyright notice remains in [LICENSE](LICENSE).

## Offline speech models

The build script downloads two speech recognition models from the official [Vosk model catalog](https://alphacephei.com/vosk/models):

- `vosk-model-small-en-us-0.15` — English (US), Apache License 2.0
- `vosk-model-small-ja-0.22` — Japanese, Apache License 2.0

The original models and their Apache License 2.0 terms must be preserved when redistributing a packaged APK. Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Other components

- [Vosk Android SDK](https://github.com/alphacep/vosk-api): consult the dependency license and transitive notices.
- [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation): subject to Google's ML Kit terms and the device's Google Play services.
- AndroidX and Kotlin components retain their respective third-party licenses.

No remote translation service, advertising, analytics, error reporting, or payment SDK is part of the modified runtime.
