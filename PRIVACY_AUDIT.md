# 개인정보 보호 및 빌드 검증 (2026-10-14)

## 적용한 변경

- `AndroidManifest.xml`에서 앱의 `INTERNET` 및 `ACCESS_NETWORK_STATE` 권한을 병합 단계에서 제거하도록 설정.
- Firebase Analytics, Crashlytics, Performance / AdMob / 결제 / Cloudflare 오류 제보 및 외부 피드백 / 자동 업데이트 / 원격 Whisper / LibreTranslate 소스 및 의존성을 제거.
- 외부 전송 요청(HTTP POST, 원격 STT/번역), 텍스트 로그, 기록 공유 인텐트, 클립보드 복사 기능 제거.
- `allowBackup=false` 및 데이터 추출 규칙으로 앱 데이터의 Google 백업·기기 간 전송을 차단.
- 기록 자동 저장을 끄고, 명시적 선택 시에만 기기 내부에 저장.
- 영어·일본어 Vosk 음성 모델은 설치된 앱이 아닌 *APK 빌드 머신*에서 공식 서버로부터 내려받아 포함.
- 앱 기본값은 동영상 내부 음성 → 영어 인식 → 한국어 번역으로 설정.

## 예외와 한계

Google ML Kit 번역 모델은 최초 사용 때 Android의 Google Play 서비스를 통해 다운로드할 수 있다. 모델 요청에 수반되는 네트워크 메타데이터는 Google 서비스 영역이며, 앱 자체의 음성/자막 원문을 원격 번역 서버에 POST하지 않는다. Android 시스템 및 다른 앱의 네트워크 통신은 이 APK의 `INTERNET` 권한 설정만으로 제한할 수 없다.

사용자가 만든 APK의 실제 동작을 검증하려면 최소 다음 테스트가 필요하다.

1. CI에서 `testDebugUnitTest` 및 `assembleDebug` 통과.
2. 산출 APK의 병합 매니페스트에 `android.permission.INTERNET` 부재 확인.
3. English/Japanese Vosk 모델이 앱 `assets/models`에 포함됐는지 확인.
4. 실제 Android 기기에 설치 후 YouTube 영어/일본어 영상에서 한국어 오버레이가 정상 작동하는지 확인.
5. 최초 ML Kit 모델 준비 과정 및 준비 완료 후 오프라인 처리 확인.
6. 네트워크 모니터링으로 앱 UID의 외부 송신이 없는지 검증.

**현재 상태**: 소스 코드 정적 검토 완료. APK 빌드 및 실기기 네트워크 추적은 미실시. 빌드 성공 또는 데이터 송신 0건을 실측 검증했다고 주장하지 않는다.

MIT 라이선스와 원본 저작권 고지를 유지한다.
