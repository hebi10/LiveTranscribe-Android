# Google Play 최초 등록

대상은 `hebi10/LiveTranscribe-Android`의 `com.hebi.livetranslate`입니다. 다른 앱의 서명키·배포 계정·등록 정보를 사용하지 않습니다.

## 등록 정보

| 항목 | 준비 값 |
| --- | --- |
| 앱 이름 | 실시간 영상 번역 |
| 기본 언어 | 한국어 `ko-KR` |
| 유형·가격 | 앱·무료 |
| 분류 | 도구 |
| 연락처 | asdlkj0104@gmail.com |
| 개인정보처리방침 | https://hebi10.github.io/LiveTranscribe-Android/privacy-policy.html |
| 대상 기기 | Android 10 이상, arm64-v8a |
| 초기 배포 | 내부 테스트 |
| 비공개 테스트 | 사용자 요청 시 검증된 내부 버전을 `alpha`로 승격 |

`listing-ko.json`이 제목·간단한 설명·전체 설명의 원본입니다. 이미지 업로드는 실제 Android 캡처를 검수하고 `assets/play-store/ko-KR/manifest.json`에 명시한 뒤에만 수행합니다. 드라이브의 2026-09-18 이미지에는 깨진 글리프와 근거 없는 통화 번역 문구가 있어 재사용하지 않습니다.

## 심사 접근 안내

로그인, 유료 구독, 클라우드 API 키 또는 개발자 서버 계정이 없습니다. 심사 전용 서버가 필요하지 않습니다.

1. 앱을 열고 동영상 음성 또는 마이크 입력을 선택합니다.
2. 음성 권한을 허용합니다. 팝업 사용 시 다른 앱 위 표시 권한도 허용합니다.
3. 앱의 모델 준비를 눌러 약 2.12GB의 고정된 음성 인식·번역 모델을 받습니다. 네트워크와 저장 공간이 필요합니다.
4. 준비 완료 후 번역 시작을 누릅니다. 동영상 음성 모드는 Android의 화면 공유 확인을 진행합니다.
5. 시스템 알림이나 앱의 종료 버튼으로 중지합니다.

캡처 차단 앱, DRM 콘텐츠, 일부 기기는 내부 소리를 제공하지 않을 수 있습니다. 마이크 입력으로도 기능을 확인할 수 있습니다. 모델 준비 전 UI 캡처는 실제 모델 추론 성공의 증거가 아닙니다.

## 정책 신고 초안과 확인 범위

| 신고 | 소스에서 확인한 내용 | 제출 시 처리 |
| --- | --- | --- |
| 광고 | 광고 SDK·광고 표시 없음 | 광고 없음 |
| 앱 액세스 | 로그인·결제·서버 인증 없음 | 모든 기능 접근 가능, 위 모델 준비 안내 첨부 |
| 개인정보처리방침 | 앱 내 고정 외부 링크, 공개 HTML 원본 | 공개 URL의 HTTP 성공과 본문 확인 후 입력 |
| 계정 삭제 | 앱 계정·회원가입 없음 | 계정 생성 없음 |
| 마이크 | 사용자가 시작한 음성 인식 입력 | 실행 중 알림·중지 경로 설명 |
| 화면 캡처 | MediaProjection으로 허용된 동영상의 소리 사용 | `mediaProjection` 전경 서비스 사용 사례 설명·실제 시연 영상 첨부 |
| 마이크 전경 서비스 | 사용자가 시작한 마이크 음성의 연속 자막 | `microphone` 사용 사례 설명·실제 시연 영상 첨부 |
| 다른 앱 위 표시 | 사용자 선택 팝업 자막 | 필수 기능·명시적 권한 요청 설명 |
| 뉴스·금융·건강·정부 기능 | 해당 기능 없음 | 해당 없음 |
| 아동 대상 | 성인·일반 도구를 기준으로 준비 | 실제 대상 연령을 확인하고 최종 신고 |
| 콘텐츠 등급 | 사용자 음성을 번역하는 도구 | 실제 Console 질문에 맞춰 답하고 산출 등급 수신 |

데이터 보안에서 음성·인식문·번역문은 소스상 외부 전송하지 않고 로컬 처리합니다. 선택적 기록은 앱 내부 비백업 저장소에 최대 500개 보관합니다. 광고·분석·결제·오류 수집 SDK가 없습니다. 이것만으로 모든 데이터 유형에 대해 "수집 없음"을 확정하지 않습니다. 사용자가 요청한 모델 다운로드의 Hugging Face/CDN IP·요청 메타데이터, 외부 브라우저 정책 페이지의 GitHub 접속 기록은 해당 서버의 실제 목적·보관 및 Play 정의에 따라 최종 검토합니다. 정적 검사는 전체 SDK 통신·실기기 패킷 검증을 대체하지 않습니다.

생성형 AI 정책의 생산성 도구 범위와 번역 기능 적용 여부도 실제 신고 화면과 공식 정책 기준으로 판단합니다. 공개형 챗봇·이미지 생성 기능으로 신고하지 않습니다. 공식 문서에 없는 예외를 임의로 확정하거나 사용하지 않는 신고 기능을 추가하지 않습니다.

## 서명 및 최초 업로드

기존 앱의 인증서 계보를 유지합니다. `google-play` 환경의 기존 키·암호·alias·SHA-256이 없으면 릴리스 빌드를 중단합니다. 새 키, 디버그 키, 다른 앱 키로 대체하지 않습니다. Play App Signing도 기존 설치 앱의 업데이트 호환성을 유지하는 인증서를 사용해야 합니다. Google 생성 키를 임의로 선택하면 기존 설치와 호환되지 않을 수 있습니다.

처음 앱을 만들 때 필요한 개발자 정책 준수 선언·미국 수출법 동의, 기존 키 확보, Play App Signing 등록과 최초 서명 AAB 업로드를 완료하기 전에는 자동 배포를 활성화하지 않습니다. 첫 AAB가 등록되고 Console의 패키지·인증서가 확인된 뒤 `PLAY_CONSOLE_INITIALIZED=true`를 설정합니다. 앱 생성과 내부 테스트 설치 가능 상태는 별개입니다.

## 자동화 설정

전용 서비스 계정과 main 전용 WIF를 권장합니다. 기존 프로젝트를 사용할 경우에도 이 저장소·`refs/heads/main`·필요한 배포 환경으로 제한하고 Play의 이 앱에 테스트 출시와 스토어 등록정보 관리 권한만 부여합니다. 보안 접근 확대는 최종 설정을 확인한 뒤 수행합니다.

| 종류 | 이름 |
| --- | --- |
| 환경 | `google-play` 새 Play 빌드·서명·배포, `release-signing` 기존 수동 APK 경로 |
| 서명 Secrets | `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`, `ANDROID_RELEASE_CERT_SHA256` |
| Play Variables | `PLAY_WORKLOAD_IDENTITY_PROVIDER`, `PLAY_SERVICE_ACCOUNT`, `PLAY_CONSOLE_INITIALIZED` |
| 선택적 대체 인증 | `PLAY_SERVICE_ACCOUNT_JSON` (WIF 미사용 시에만) |

새 Play 워크플로는 `google-play` 환경을 사용하므로 다섯 서명 Secrets를 그 환경에 준비해야 합니다. 기존 수동 APK 작업의 `release-signing`에만 있는 값은 전달되지 않습니다. 같은 기존 키를 사용하며 새 키를 생성하지 않습니다. 두 환경 모두 허용 배포 브랜치는 main으로 제한합니다. 인증 정보는 코드·출력·일반 문서에 기록하지 않습니다.

`Private Android APK`의 정확한 main 커밋 검증이 성공한 뒤 내부 배포를 진행합니다. AAB·APK의 기존 인증서, 모든 포함 네이티브 라이브러리의 ELF 정렬, APK ZIP 정렬, AAB의 16KB 설정을 검사합니다. 16KB 실기기 실행은 별도 검증 항목입니다. 버전 코드는 Play의 모든 트랙·APK·AAB보다 큰 값으로 할당하며 업로드 직전에 다시 확인합니다.

API 커밋 후 새 편집에서 버전·트랙·등록정보를 다시 읽고, Console의 심사·게시 상태와 실제 테스터 설치도 별도로 확인합니다. 프로덕션 배포는 이 작업 범위에 포함하지 않습니다.

## 공식 근거

- https://support.google.com/googleplay/android-developer/answer/10787469
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://support.google.com/googleplay/android-developer/answer/14094294
- https://huggingface.co/privacy
- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection

## 현재 상태

2026-11-08: PR #16으로 소스·스토어 문구·검증 자동화를 main에 반영했습니다. Python 113개, Android 단위 테스트·Lint·arm64 APK·실제 APK 16KB 정렬과 API35 계측 검증을 통과했습니다. 공개 방침은 GitHub Pages로 배포합니다. 등록용 이미지는 실제 화면 4장과 기존 앱 아이콘·배너로 구성하며, 시각 검수에서 중복된 개인정보 화면 캡처는 제외했습니다. Google Play 배포 환경은 main 브랜치만 허용하며 아직 Secrets가 없습니다. 앱 생성 화면에서 이름·패키지·한국어·무료 값을 준비했으며 패키지 사용 가능을 확인했습니다. 법적 선언은 아직 수락하지 않았고 앱 생성·서명 AAB 업로드·실제 Play 배포 완료를 의미하지 않습니다.
