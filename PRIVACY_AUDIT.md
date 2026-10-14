# 개인정보 보호 및 빌드 검증 (2026-10-14)

## 적용한 변경

- `AndroidManifest.xml`에서 앱의 `INTERNET` 및 `ACCESS_NETWORK_STATE` 권한을 병합 단계에서 제거하도록 설정.
- Firebase Analytics, Crashlytics, Performance / AdMob / 결제 / Cloudflare 오류 제보 및 외부 피드백 / 자동 업데이트 / 원격 Whisper / LibreTranslate 소스 및 의존성을 제거.
- 외부 전송 요청(HTTP POST, 원격 STT/번역), 텍스트 로그, 기록 공유 인텐트, 클립보드 복사 기능 제거.
- `allowBackup=false` 및 데이터 추출 규칙으로 앱 데이터의 Google 백업·기기 간 전송을 차단.
- 기록 자동 저장을 끄고, 명시적 선택 시에만 기기 내부에 저장.
- 영어·일본어 Vosk 음성 모델과 Whisper Tiny 언어 식별 모델, sherpa-onnx Android AAR을 설치된 앱이 아닌 *APK 빌드 머신*에서 공식 저장소로부터 내려받아 포함.
- 음성 언어 추천은 두 번 연속 확정된 발화 구간의 PCM을 기기 메모리에서 분석합니다. 오디오 클립과 감지 결과는 서버나 앱 파일에 저장하지 않습니다.
- ML Kit 번역 모델은 사용자가 앱에서 버튼을 눌렀을 때에만 Wi-Fi 다운로드를 요청하도록 변경. 시작 전에 모델 준비 완료 여부 확인.
- 앱 기본값은 동영상 내부 음성 → 영어 인식 → 한국어 번역으로 설정.

## 예외와 한계

Google ML Kit 번역 모델은 최초 사용 때 Google Play 서비스 등을 이용해 다운로드될 수 있다. Google이 공지한 SDK 데이터 수집 범위에는 기기·앱 정보, 사용/성능 지표, **설정한 번역 출발어와 도착어**, 진단 이벤트 등이 포함된다(https://developers.google.com/ml-kit/android-data-disclosure). 이 앱에는 녹음한 음성이나 번역문 원문을 외부 번역 서버로 POST하는 코드는 없지만, Google SDK 및 시스템 서비스의 진단/모델 다운로드 통신을 모두 차단한다고 보장할 수 없다. **요구사항이 '외부 데이터 송신 0건'이라면 ML Kit 의존성을 제거하고 APK에 번들된 독립 실행형 번역 엔진으로 대체한 뒤 실기기 패킷 검증이 필요하다.**

사용자가 만든 APK의 실제 동작을 검증하려면 최소 다음 테스트가 필요하다.

1. CI에서 `testDebugUnitTest` 및 `assembleDebug` 통과.
2. 산출 APK의 병합 매니페스트에 `android.permission.INTERNET` 부재 확인.
3. English/Japanese Vosk 모델과 Whisper Tiny int8 ONNX 파일 및 sherpa-onnx AAR이 최종 APK에 포함됐는지 확인.
4. 실제 Android 기기에 설치 후 YouTube 영어/일본어 영상에서 한국어 오버레이가 정상 작동하는지 확인.
5. 사용자가 모델 준비를 누르기 전에는 ML Kit 다운로드가 발생하지 않는지 확인하고, Wi-Fi 모델 준비 완료 후 오프라인 번역을 확인.
6. 다른 언어의 독립 발화 2회 연속 감지 → 한국어 UI와 오버레이 추천 → 사용자가 승인하면 권한 재요청 후 재시작 동작 확인.
7. 모델 초기화 전/후 앱 UID와 Google Play 서비스 등 관련 시스템 프로세스의 네트워크 통신을 함께 분석.

**현재 상태**: 소스 코드 정적 검토 완료. APK 빌드 및 실기기 네트워크 추적은 미실시. 빌드 성공 또는 데이터 송신 0건을 실측 검증했다고 주장하지 않는다.

MIT 라이선스와 원본 저작권 고지를 유지한다.


## 1.0.1 보완
- 번역 기록을 `noBackupFilesDir`로 이동하며 최초 앱 실행 시 기존 기록의 이전을 시도합니다.
- Android 데이터 추출 규칙에서 root, file, database, sharedpref, external 및 device 영역을 명시적으로 제외합니다.
- 기존 복구 사본은 이전 시 사용하지 않고 삭제합니다. 삭제 후 복구 사본에는 최신 데이터만 남습니다.
- 소스 검사 결과는 앱 코드·선언 검사이며 SDK 내부 수집·최종 APK·기기 네트워크 검증을 대신하지 않습니다.
- INTERNET 권한 제거와 ML Kit 최초 모델 다운로드 성공은 별도 검증 대상입니다. 확인되지 않은 시스템 위임 다운로드를 전제로 하지 않습니다.
