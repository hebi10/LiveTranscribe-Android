# 실시간 영상 번역 (개인용)

LiveCaptionN의 MIT 라이선스 소스코드를 기반으로 한 **한국어 개인용 오프라인 영상 자막 앱**입니다.

## 주요 기능
- Android 10 이상에서 캡처가 허용된 다른 앱의 재생 음성 인식
- Vosk를 이용한 영어·일본어 음성의 기기 내 인식
- Google ML Kit을 이용한 한국어 번역 및 드래그 가능한 자막 오버레이
- 자막 크기, 투명도, 원문 표시, 번역 기록 저장 여부 설정
- 초기 설정: 동영상 음성 / 영어 → 한국어 / 원문 숨김 / 기록 저장 끔

## 개인정보 보호
- Android APK에 **INTERNET 권한이 없음** (병합된 매니페스트에서도 제거하도록 강제)
- Firebase, AdMob, Google Play Billing, Stripe, Cloudflare, 오류 제보, 피드백, 원격 Whisper, LibreTranslate, GitHub 릴리스 자동 확인 제거
- 음성·번역문을 Android 디버그 로그에 기록하지 않도록 수정
- 앱 백업 및 다른 기기로의 데이터 전송 백업 차단
- 기록은 사용자가 명시적으로 저장을 켠 경우에만 앱 내부 파일에 기록

**중요:** Google ML Kit은 번역 모델을 처음 사용할 때 Google Play 서비스에서 모델을 다운로드할 수 있습니다. 이때 기기·요청 메타데이터가 Google에 전달될 가능성은 있습니다. **영상 음성이나 번역할 문장을 앱에서 외부 서버로 전송하는 경로는 제거했습니다.** 사전 모델 다운로드가 완료된 후 번역 자체는 기기에서 처리합니다. Android/Google 시스템 프로세스 및 타 앱의 통신을 이 앱의 권한 설정만으로 차단할 수는 없습니다.

## 빌드
JDK 17, Android SDK 및 Python 3이 필요합니다. 음성 모델은 **개발 환경에서만** 공식 서버로부터 다운로드해 APK에 포함합니다. 완성된 앱에서는 다운로드 기능이 없습니다.

```bash
python scripts/prepare_vosk_models.py
./gradlew testDebugUnitTest assembleDebug
```

Windows PowerShell:
```powershell
python scripts/prepare_vosk_models.py
./gradlew.bat testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions의 **Private Android APK** 워크플로도 같은 과정을 실행해 APK 아티팩트를 생성합니다. Debug APK는 개발용 서명으로 만들어지므로, 지속적인 업데이트 배포를 위해서는 별도 서명 체계가 필요합니다.

## 제한 사항
- 보호된 콘텐츠나 오디오 캡처를 차단한 앱에서는 시스템 음성을 인식할 수 없습니다.
- 현재 APK에 포함되는 음성 인식 모델은 영어·일본어입니다. 언어 자동 감지는 지원하지 않습니다.
- Vosk small 모델은 고유명사, 빠른 대화, 잡음에 따른 인식 오류가 발생할 수 있습니다.
- 최초 ML Kit 모델 준비 전에는 한국어 번역을 바로 사용할 수 없을 수 있습니다.

원본 저작권 및 MIT 라이선스 고지는 [LICENSE](LICENSE)에 유지됩니다.
