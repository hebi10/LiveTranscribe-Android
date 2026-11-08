# Google Play 최초 등록

대상은 `hebi10/LiveTranscribe-Android`의 `com.hebi.livetranslate`입니다. 다른 앱의 서명키·배포 계정·등록 정보를 사용하지 않습니다.

## 등록 정보

| 항목 | 준비 값 |
| --- | --- |
| 앱 이름 | 실시간 영상 번역 |
| 패키지·Console 앱 ID | `com.hebi.livetranslate` · `4974215389776889403` |
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

## 정책 신고와 확인 범위

| 신고 | 소스에서 확인한 내용 | Console 상태·남은 확인 |
| --- | --- | --- |
| 광고 | 광고 SDK·광고 표시 없음 | 광고 없음 저장 완료 |
| 앱 액세스 | 로그인·결제·서버 인증 없음 | 로그인 등 접근 제한 없음 저장 완료, 모델 준비 절차는 위 안내 참고 |
| 개인정보처리방침 | 앱 내 고정 외부 링크, 공개 HTML 원본 | 공개 방침 URL 저장 완료 |
| 계정 삭제 | 앱 계정·회원가입 없음 | 계정 생성 없음 |
| 마이크 | 사용자가 시작한 음성 인식 입력 | 실행 중 알림·중지 경로 설명 |
| 화면 캡처 | MediaProjection으로 허용된 동영상의 소리 사용 | `mediaProjection` 전경 서비스 사용 사례 설명·실제 시연 영상 첨부 |
| 마이크 전경 서비스 | 사용자가 시작한 마이크 음성의 연속 자막 | `microphone` 사용 사례 설명·실제 시연 영상 첨부 |
| 다른 앱 위 표시 | 사용자 선택 팝업 자막 | 필수 기능·명시적 권한 요청 설명 |
| 정부 앱 | 정부 기관·정부 서비스 기능 없음 | 정부 앱 아님 저장 완료 |
| 금융 기능 | 해당 기능 없음 | 금융 기능 없음 저장 완료 |
| 건강 기능 | 해당 기능 없음 | 건강 기능 없음 저장 완료 |
| 뉴스 | 뉴스 발행 기능 없음 | 별도 신고 완료를 의미하지 않음 |
| 아동 대상 | 성인·일반 도구를 기준으로 준비 | 미완료: 실제 대상 연령을 확인하고 최종 신고 |
| 콘텐츠 등급 | 사용자 음성을 번역하는 도구 | 미완료: 실제 Console 질문에 맞춰 답하고 산출 등급 수신 |
| 스토어 설정 | 도구 분류·공개 문의 이메일 준비 | 도구 분류와 문의 이메일 저장 완료, 문구·이미지 업로드는 미완료 |
| 판매자 상태 | 무료·광고 없음·인앱 결제 없음 | 미확정: 실제 상업 활동 및 EEA·UK 배포 범위를 확인해야 함 |

데이터 보안에서 음성·인식문·번역문은 소스상 외부 전송하지 않고 로컬 처리합니다. 선택적 기록은 앱 내부 비백업 저장소에 최대 500개 보관합니다. 광고·분석·결제·오류 수집 SDK가 없습니다. 이것만으로 모든 데이터 유형에 대해 "수집 없음"을 확정하지 않습니다. 사용자가 요청한 모델 다운로드의 Hugging Face/CDN IP·요청 메타데이터, 외부 브라우저 정책 페이지의 GitHub 접속 기록은 해당 서버의 실제 목적·보관 및 Play 정의에 따라 최종 검토합니다. 정적 검사는 전체 SDK 통신·실기기 패킷 검증을 대체하지 않습니다.

[Google의 Data Safety 공식 안내](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)는 내부 테스트 트랙에만 활성인 앱은 해당 양식을 작성할 필요가 없다고 설명합니다. 현재는 내부 테스트를 준비하는 단계이므로 이 예외를 근거로 미확정 답변을 제출하지 않습니다. 비공개·공개 테스트 또는 프로덕션으로 확대하기 전에는 정확한 양식을 완료해야 합니다.

IP는 실제 처리 목적에 따라 분류하며, IP가 통신에 사용된다는 이유만으로 기기 ID나 위치를 일괄 선택하지 않습니다. [Hugging Face의 방침](https://huggingface.co/privacy)과 [다운로드 분석 문서](https://huggingface.co/docs/hub/publisher-analytics)는 접속 기록·해시 IP·국가·지역 등의 처리 가능성을 설명합니다. 해당 모델 다운로드에서의 실제 처리 범위가 미확인인 만큼 기기 ID·대략적 위치·일시적 처리 여부를 확정하지 않습니다. 사용자 요청 다운로드와 서비스 제공자에 관한 공유 예외도 수집 전체의 면제로 해석하지 않습니다.

판매자 여부는 [Google의 공식 판단 기준](https://support.google.com/googleplay/android-developer/answer/17594752)에 따라 수익 의도와 사업·직업 관련 활동 등을 확인해야 합니다. 이 앱이 무료이고 광고·결제가 없다는 사실만으로 비판매자 상태를 선언하지 않습니다.

생성형 AI 정책의 생산성 도구 범위와 번역 기능 적용 여부도 실제 신고 화면과 공식 정책 기준으로 판단합니다. 공개형 챗봇·이미지 생성 기능으로 신고하지 않습니다. 공식 문서에 없는 예외를 임의로 확정하거나 사용하지 않는 신고 기능을 추가하지 않습니다.

## 서명 및 최초 업로드

사용자 승인에 따라 이전 직접 설치 APK와의 업데이트 호환성을 이어받지 않고, 이 앱 전용의 새 업로드 키를 최초 Play 배포 기준으로 준비했습니다. 이 키와 공개 인증서의 일치를 확인하고 `google-play` 환경에 다섯 서명 Secrets를 등록했습니다. 키 백업은 앱별 비공개 보관 위치에 저장하고 소유자 전용 접근을 확인했습니다. 키·암호·개인 보관 경로는 공개 저장소에 기록하지 않습니다.

이후 빌드는 준비한 업로드 키의 인증서를 `ANDROID_RELEASE_CERT_SHA256`에 고정해 검증합니다. 키가 없거나 지문이 다르면 중단하며, 디버그 키·다른 앱의 키·실행 중 임의 생성한 키로 대체하지 않습니다. 코드와 작업 로그의 "existing release signer"는 이제 이 명시적으로 준비한 업로드 서명자를 뜻하며, 이전 APK의 키 복구를 요구하는 의미가 아닙니다.

Play App Signing은 최초 AAB 업로드 과정에서 등록·확인합니다. 업로드 인증서와 Google Play가 설치본에 사용하는 앱 서명 인증서는 역할이 다르므로 각각 기록하고 확인합니다. 기존 APK 인증서 가져오기는 필요하지 않습니다. 이전 직접 설치 APK는 새 Play 설치본과 호환되는 업데이트로 취급하지 않으며, 직접 배포용 APK와 Play 설치본도 서명 방식에 따라 다를 수 있습니다.

개발자 정책 준수 선언·미국 수출법 동의를 거쳐 Console 앱 생성을 완료했습니다. 최초 서명 AAB 업로드와 Play App Signing 등록은 아직 남아 있습니다. 첫 AAB가 등록되고 Console의 패키지·업로드 및 앱 서명 인증서, 전용 계정의 필요한 Play 권한이 확인된 뒤 `PLAY_CONSOLE_INITIALIZED=true`를 설정합니다. 그 전에는 자동 API 배포를 활성화하지 않습니다. 앱 생성과 내부 테스트 설치 가능 상태는 별개입니다.

## 자동화 설정

프로젝트 `hebi-app-release-80847404`에 전용 서비스 계정 `play-livetranscribe@hebi-app-release-80847404.iam.gserviceaccount.com`과 전용 WIF 풀 `livetranscribe-github`·공급자 `github-main`을 준비했습니다. 서비스 계정의 JSON 키와 프로젝트 수준 역할은 만들거나 부여하지 않았습니다.

WIF는 저장소 이름·저장소 ID `1418497482`·소유자 ID `80847404`·`refs/heads/main`·`google-play` 환경을 함께 확인하도록 제한했습니다. 해당 저장소 ID의 principalSet에 이 서비스 계정의 `roles/iam.workloadIdentityUser`만 부여한 구성을 확인했습니다. GitHub에는 WIF 공급자와 서비스 계정 변수를 등록했습니다.

Play Console에서는 전용 서비스 계정이 이 앱에만 초대·활성화된 상태를 확인했습니다. 권한은 앱 정보·품질 정보 조회, 테스트 출시·테스트 트랙 관리, 스토어 등록정보 관리 및 그에 기본으로 포함되는 정책 신고 관리입니다. 계정 수준 권한, 프로덕션 출시, 관리자 및 재무 권한은 부여하지 않았습니다. 실제 Play API 접근 성공 여부는 최초 AAB 업로드 이후 별도로 검증합니다.

| 종류 | 이름 |
| --- | --- |
| 환경 | `google-play` 새 Play 빌드·서명·배포, `release-signing` 기존 수동 APK 경로 |
| 서명 Secrets | `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`, `ANDROID_RELEASE_CERT_SHA256` |
| 등록한 Play Variables | `PLAY_WORKLOAD_IDENTITY_PROVIDER`, `PLAY_SERVICE_ACCOUNT` |
| 최초 업로드 확인 후 설정 | `PLAY_CONSOLE_INITIALIZED=true` |
| 선택적 대체 인증 | `PLAY_SERVICE_ACCOUNT_JSON` (현재 미사용·미등록) |

새 Play 워크플로의 `google-play` 환경은 main 브랜치만 허용하며, 준비한 새 업로드 키의 다섯 서명 Secrets를 등록했습니다. 기존 수동 APK 작업의 `release-signing` 환경에 있는 값은 전달되지 않으며, 그 환경에 새 키가 등록되었다고 가정하지 않습니다. 수동 APK 경로를 사용할 때도 별도로 main 제한과 서명 설정을 확인합니다. 인증 정보는 코드·출력·일반 문서에 기록하지 않습니다.

`Private Android APK`의 정확한 main 커밋 검증이 성공한 뒤 내부 배포를 진행합니다. AAB·APK의 고정한 업로드 인증서, 모든 포함 네이티브 라이브러리의 ELF 정렬, APK ZIP 정렬, AAB의 16KB 설정을 검사합니다. 16KB 실기기 실행은 별도 검증 항목입니다. 버전 코드는 Play의 모든 트랙·APK·AAB보다 큰 값으로 할당하며 업로드 직전에 다시 확인합니다.

API 커밋 후 새 편집에서 버전·트랙·등록정보를 다시 읽고, Console의 심사·게시 상태와 실제 테스터 설치도 별도로 확인합니다. 프로덕션 배포는 이 작업 범위에 포함하지 않습니다.

## 공식 근거

- https://support.google.com/googleplay/android-developer/answer/10787469
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://support.google.com/googleplay/android-developer/answer/14094294
- https://support.google.com/googleplay/android-developer/answer/17594752
- https://huggingface.co/privacy
- https://huggingface.co/docs/hub/publisher-analytics
- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection

## 현재 상태

2026-11-08: PR #16으로 소스·스토어 문구·검증 자동화를 main에 반영했습니다. Python 113개, Android 단위 테스트·Lint·arm64 APK·실제 APK 16KB 정렬과 API35 계측 검증을 통과했습니다. 공개 방침은 GitHub Pages로 배포합니다. 등록용 이미지는 실제 화면 4장과 기존 앱 아이콘·배너로 구성하며, 시각 검수에서 중복된 개인정보 화면 캡처는 제외했습니다.

| 단계 | 확인 상태 |
| --- | --- |
| Console 앱 생성 | 완료: 실시간 영상 번역 · `com.hebi.livetranslate` |
| 기본 Console 신고·스토어 설정 | 개인정보처리방침 URL·로그인 제한 없음·광고 없음·정부 앱 아님·금융 및 건강 기능 없음·도구 분류·문의 이메일 저장 완료 |
| Console 대시보드 | 12개 중 7개 완료 확인, 남은 5개는 콘텐츠 등급·대상 연령·Data Safety·판매자 상태·스토어 등록정보 |
| 남은 Console 등록 | 대상 연령·콘텐츠 등급·판매자 상태·스토어 문구 및 이미지 미완료, Data Safety는 내부 전용 예외 범위에서 미제출 유지 |
| 신규 업로드 서명자 | 준비·인증서 일치 확인·main 전용 환경의 Secrets 등록·소유자 전용 백업 확인 완료 |
| 전용 서비스 계정·WIF | Cloud 구성·GitHub 변수 등록·이 앱에 한정된 Play 계정 활성화와 권한 확인 완료, 실제 API 접근은 검증 전 |
| 최초 서명 AAB 빌드 | [실행 #42490409646](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/42490409646) 성공: `1.3.1` · code `14` · `com.hebi.livetranslate`, 업로드 인증서 고정 검증과 AAB·APK 16KB 정렬 검사 통과 |
| 릴리스 파일 보관 | 검증된 AAB·APK ZIP의 소유자 전용 보관 확인 완료 |
| 최초 AAB 업로드·Play App Signing | 미완료: Console이 아직 첫 AAB를 수신하지 않음, 사용자의 최초 파일 업로드 단계로 인계 |
| 자동 API 배포 활성화 | 미완료: `PLAY_CONSOLE_INITIALIZED` 미설정 상태 유지 |
| 내부 테스트 준비 | `1.3.1 (14)` 초안의 출시 노트와 본인 테스터 목록 저장 완료, AAB가 없는 미게시 초안 |
| 내부 테스트 | Console에서 주소를 확인했으나 현재 비활성 상태, 릴리스 게시·설치 가능 여부 확인 전 |

확인된 내부 테스트 주소는 https://play.google.com/apps/internaltest/4701453452188240193 입니다. 주소 확보나 서명 빌드 성공은 배포 완료를 의미하지 않습니다. 사용자가 Console에서 최초 AAB 파일을 업로드한 뒤 패키지·업로드 및 앱 서명 인증서 확인, 릴리스 게시, API 접근·읽기 확인과 실제 테스터 설치 확인을 순서대로 마무리해야 합니다. 최초 업로드 전에는 부트스트랩 가드를 해제하지 않습니다.
