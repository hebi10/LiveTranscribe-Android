# 개인정보 보호 및 빌드 검증 (1.3.0 개발, 2026-10-29)

## 현재 처리 경로

- 음성 인식은 **Qwen3-ASR 0.6B int8 + sherpa-onnx 1.13.8**, 한국어 번역은 **Hy-MT2 1.8B Q4_K_M + llama.cpp CPU**로 기기에서 실행합니다. Whisper Tiny는 음성 구간의 언어 식별에 사용하고 Silero VAD는 발화 구간을 찾습니다.
- 현재 실행 의존성에 ML Kit과 Vosk SDK는 없습니다. 과거 모델 준비 스크립트와 그 회귀 테스트는 현재 APK에 해당 엔진이나 모델이 포함된다는 뜻이 아닙니다.
- Qwen3-ASR와 Hy-MT2의 대형 모델은 APK에 넣지 않습니다. 사용자가 앱의 모델 준비를 요청하면 `OfflineModelCatalog`에 고정된 Hugging Face HTTPS 주소에서 받고, HTTPS CDN 리디렉션을 따릅니다. Wi-Fi와 모바일 데이터를 모두 사용할 수 있습니다.
- 다운로드 파일의 크기와 SHA-256이 일치해야 준비 완료로 인정합니다. 모델과 중단된 다운로드는 앱의 `noBackupFilesDir/ai-models`에 보관합니다. 앱 삭제나 데이터 삭제 시에는 다시 준비해야 합니다.
- 빌드 머신은 고정된 sherpa-onnx AAR, Whisper Tiny 언어 식별 모델, Silero VAD, llama.cpp 소스를 준비합니다. APK에는 작은 음성 지원·언어 식별 모델과 로컬 실행 라이브러리가 포함됩니다.
- 음성과 인식·번역 문장은 로컬 엔진에 전달합니다. 알려진 앱 코드에 이를 외부 음성 인식·번역 API로 전송하는 경로는 없습니다. 음성 구간과 언어 식별 입력은 메모리에서 처리하며 오디오 파일로 기록하지 않습니다.

## 통신 범위와 한계

`INTERNET`과 `ACCESS_NETWORK_STATE` 권한은 모델 다운로드를 위해 필요합니다. 유일한 앱 네트워크 구현인 `ModelDownloadTransport`는 고정 모델 URL의 GET 요청과 HTTPS 리디렉션을 처리합니다. 요청 본문이나 업로드 스트림을 만들지 않으며, 음성·자막·번역문·계정 토큰·쿠키·앱 로그를 입력으로 받지 않습니다. 재개 다운로드에는 `Range` 헤더를 사용합니다.

다운로드 서버와 CDN은 IP 주소, 요청한 모델 경로, 전송 시각 등 일반적인 접속 정보를 볼 수 있습니다. Android 네트워크 권한 자체가 통신을 이 용도로 제한해 주지는 않습니다. 모델 설치 후 인식과 번역에는 네트워크가 필요하지 않지만, 실제 기기에서 통신 0건을 관측했다는 의미는 아닙니다.

Firebase Analytics/Crashlytics/Performance, 광고·결제·원격 오류 제보·피드백·자동 업데이트 SDK는 사용하지 않습니다. ML Kit 제거로 과거 문서의 ML Kit 진단 수집 안내는 현재 실행 경로에 해당하지 않습니다. 원문을 남기는 텍스트 로그, 기록 공유 인텐트, 클립보드 복사 기능도 사용하지 않습니다.

## 기록과 백업

- 화면 자막은 메모리에 보관합니다. 종료한 분할 화면의 완료 자막은 다음 번역 시작이나 앱 프로세스 종료까지 남을 수 있습니다. 화면 표시 방식 선택만으로 파일 기록이 켜지지 않습니다.
- 기록 자동 저장은 기본으로 꺼져 있습니다. 사용자가 켠 경우에만 내부 기록 파일에 원문과 번역을 저장합니다.
- 기록은 `noBackupFilesDir`에 보관합니다. 이전 기록을 옮길 때 과거 복구 사본은 가져오지 않으며, 삭제 후 복구 사본에도 최신 상태만 남깁니다.
- `allowBackup=false`, `fullBackupContent=false`와 데이터 추출 규칙으로 Android 클라우드 백업과 기기 간 전송을 차단합니다. root, file, database, sharedpref, external 및 device 영역을 각각 제외합니다.

## 빌드와 서명

일반 push/PR 빌드는 비밀 값 없이 검사하며 **TEST-ONLY debug** APK만 만듭니다. 이 APK의 패키지 ID는 `com.hebi.livetranslate.debug`이고 버전 이름에는 `-test-only`가 붙습니다. 정식 앱 업데이트에 사용하는 아티팩트가 아닙니다.

`main`에서 `workflow_dispatch`의 `publish_preview=true`를 선택하면, 검사에 통과한 같은 실행의 TEST-ONLY debug 아티팩트를 `v1.3.0-preview` GitHub prerelease로 게시할 수 있습니다. 공개 파일 이름은 `LiveTranslate-v1.3.0-arm64-preview-separate.apk`이며 SHA-256 파일을 함께 첨부합니다. 게시 작업만 `contents: write` 권한과 표준 GitHub 작업 토큰을 사용합니다. 일반 빌드와 PR 작업은 계속 읽기 권한이며, 별도 개인 키나 서명 secret을 프리뷰에 제공하지 않습니다.

이 프리뷰는 `com.hebi.livetranslate.debug`의 **별도 설치**입니다. 기존 `com.hebi.livetranslate` 앱을 유지하고, 프리뷰 자체 모델 준비와 설정이 필요합니다. 기존 앱의 모델·설정·기록을 이전하지 않습니다. 테스트용 서명자는 이후 빌드에서 달라질 수 있으므로, 후속 프리뷰가 현재 프리뷰 위에 업데이트될 수 있다고 보장하지 않습니다. 정식 앱 업데이트가 아니며 원래 릴리스 키가 복구되었다는 의미도 아닙니다.

게시 작업은 `main`에서 명시적으로 요청하고 일반 빌드가 통과해야 실행됩니다. `v1.3.0-preview` 태그는 현재 실행의 commit에 새로 만들며, 같은 태그가 이미 있으면 실패합니다. 기존 태그나 릴리스·첨부 파일을 덮어쓰지 않습니다. 태그 생성 후 게시 자체가 실패한 경우에도 다음 실행은 기존 태그를 자동 재사용하지 않습니다.

서명된 APK는 `main`에서 `workflow_dispatch`의 `build_release=true`로 명시적으로 요청해야 합니다. 별도 `signed-release` 작업은 일반 빌드 검사가 통과한 뒤 `release-signing` GitHub Environment의 기존 키 설정을 사용합니다. PR과 fork PR 작업에는 서명 비밀 값을 연결하지 않습니다. 필수 값이 없으면 실패하며 debug 키나 새 키로 대체하지 않습니다.

설정할 때는 다음 순서를 따릅니다.

1. **이전에 배포한 앱의 원래 개인 키와 인증서를 확보합니다.** 기존 APK에 `apksigner verify --print-certs previous.apk`를 실행해 서명 인증서 SHA-256을 확인합니다. 인증서 지문은 APK 파일이나 keystore 파일 자체의 SHA-256과 다릅니다. 기존 preview APK의 서명자는 이 작업에서 확인하지 않았습니다.
2. GitHub에 `release-signing` Environment를 만들고 배포 브랜치를 `main`으로 제한합니다. 키를 제공하기 전에 필요한 승인자와 보호 규칙을 설정합니다. 실제 보호 규칙은 저장소 관리자가 설정해야 하며 YAML만으로 강제되지 않습니다.
3. 해당 Environment에 아래 다섯 secret을 등록합니다. 원래 keystore의 base64 변환 값은 안전한 로컬 경로에서 준비해 로그나 저장소에 남기지 않습니다.

| Environment secret | 값 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | 기존 배포 키를 담은 keystore의 base64 |
| `ANDROID_RELEASE_STORE_PASSWORD` | 해당 keystore 암호 |
| `ANDROID_RELEASE_KEY_ALIAS` | 기존 배포 키의 alias |
| `ANDROID_RELEASE_KEY_PASSWORD` | 해당 개인 키의 암호 |
| `ANDROID_RELEASE_CERT_SHA256` | 기존 배포 APK 서명 인증서의 SHA-256 지문 |

4. `main`에서 서명 빌드를 요청합니다. 임시 keystore는 제한된 권한으로 새로 생성한 임시 파일에만 복원하고 기존 파일을 덮어쓰지 않습니다. `keytool`로 keystore 인증서를 확인하고, Gradle 서명 후 `apksigner`로 APK 서명과 같은 인증서 지문을 다시 확인한 경우에만 아티팩트를 업로드합니다. 임시 keystore는 성공·실패와 관계없이 정리합니다.
5. 업데이트 전에 기존 설치와 패키지 ID·서명 인증서가 같고 새 APK의 `versionCode`가 더 높은지 확인합니다. 서명 검증만으로 실기기 업데이트 성공이 증명되지는 않습니다.

원래 개인 키를 보유하지 않았다면 새 무작위 키를 만들어도 기존 설치의 정상 업데이트를 만들 수 없습니다. 인증서만으로 개인 키를 복구할 수도 없습니다. 이 변경은 키를 생성하거나 GitHub secret/보호 규칙을 실제로 등록하지 않습니다.

## 검증 방법과 미검증 범위

프리뷰 게시 경로 추가 시점(2026-10-29)의 Android 빌드·lint·단위 테스트 결과는 PR CI 확인 대기 상태입니다. 이 문서는 실기기 설치나 프리뷰 게시가 완료되었다고 주장하지 않습니다. 해당 실행의 Actions 결과와 실제 릴리스 첨부 파일을 확인해야 합니다.

1. `python scripts/audit_privacy.py`와 `python -m unittest discover -s scripts -p 'test_*.py'`를 실행합니다.
2. `testDebugUnitTest`, `lintDebug`, `assembleDebug`를 실행합니다. 서명 설정을 제공한 명시적 릴리스에서는 `lintRelease`, `assembleRelease`를 실행합니다.
3. APK 권한과 `scripts/verify_apk_assets.py`로 지원 모델의 고정 해시, 로컬 번역 JNI, 대형 모델 미포함 경계를 확인합니다. 릴리스는 `scripts/release_signing.py verify-apk`의 인증서 검사도 통과해야 합니다.
4. 깨끗한 설치에서 Wi-Fi 및 모바일 데이터 각각의 모델 준비·중단·재개를 확인합니다. 준비 완료 후 네트워크를 끄고 음성 인식·언어 식별·한국어 번역을 확인합니다.
5. 모델 준비 전후와 오프라인 인식 중 앱 UID 및 관련 시스템 프로세스의 실제 네트워크를 추적합니다. 기존 버전 위에 같은 키로 업데이트한 뒤 설정과 기록 보존도 확인합니다.

정적 감사와 자동 테스트는 알려진 앱 코드의 직접 네트워크·공유 패턴, 선언, 파일 무결성 및 백업 규칙을 검사합니다. 네이티브 라이브러리 내부 동작, Android 시스템 통신, 실기기 트래픽, 모델 다운로드 성공이나 기존 앱 위의 업데이트 설치를 대신 검증하지 않습니다. 서명 도구 회귀 테스트는 합성 데이터와 대체 도구를 사용하며 실제 개인 키를 만들거나 읽지 않습니다. 실기기 모바일 데이터·트래픽 추적과 기존 키를 사용한 릴리스 설치는 별도 확인이 필요합니다.
