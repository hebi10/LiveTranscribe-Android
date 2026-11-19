# AGENTS.md — LiveTranscribe-Android

## OPS-02 — 공통 개발·검증·수동 배포 정책

### 1. 작업 범위와 근거
- 최신 main·관련 소스·기존 테스트와 Google Drive의 확정 기획·디자인·검증 문서를 확인한다. 불일치하면 보고하고 추측으로 변경하지 않는다.
- 기존 기술 스택·코드 구조·디자인 시스템·데이터·인증 및 서명 정책을 보존한다. 요청 외 리팩터링·UI 변경·신규 의존성 도입을 피한다.
- 기능 단위 브랜치·PR·검증을 분리한다. 최신 HEAD의 필수 검사 또는 QA가 실패·진행 중·미확인이라면 안전 병합하지 않는다.
### 2. GitHub Actions 대기·보고
- CI는 PR/main 변경 시 자동 검사·디버그 빌드가 가능하나 Play 변경은 금지한다.
- 새 Actions의 실제 실행 URL이 확인되면 링크·커밋·검증 상태를 안내하고, 반복 조회나 긴 대기 없이 다음 독립 작업을 제시한다.
- URL 생성이 확인되지 않았다면 실행 사실을 주장하지 않는다. 과거 SHA의 성공을 최신 SHA의 성공으로 대체하지 않는다.
### 3. 일일 Release Batch·Play 동결
- 날짜는 Asia/Seoul을 사용한다. 해당 릴리스에 계획된 모든 기능·QA·PR 병합이 완료되어야 릴리스 준비 완료로 판정한다. 미완료는 다음 작업일로 이월한다.
- 하루 종료, PR 병합, main push, 태그, 배포 마커, CI 완료, 이슈 댓글은 Play 배포 승인이 아니다. 명시적 사용자 요청 없이 Play 바이너리·이미지·아이콘·등록정보를 업로드하거나 트랙을 승격하지 않는다.
- AGENTS.md 자체는 기술적 차단 장치가 아니다. 실제 Actions·배포 스크립트의 승인·fail-closed 조건을 우회하지 않는다. 옛 문서의 자동 업로드 문구와 충돌하면 현행 사용자 수동 승인 지시를 우선한다.
### 4. 독립된 배포 요청
1. CI: 자동 lint·테스트·디버그 빌드 가능. Play 변경 없음.
2. 최종 릴리스 빌드: 계획 전체·최종 QA 완료 후 사용자 요청으로 검증된 main SHA에서 서명 AAB·SHA-256·매니페스트 생성. Play 업로드 없음.
3. 내부 테스트: 별도 요청으로 검증한 AAB를 internal에 제출. 기존 OPS-04는 동일 산출물 재사용, 기존 play-internal.yml의 deploy는 동일 main SHA에서 새 서명 빌드·검증·내부 제출로 한정한다.
   - 2026-11-16 사용자 승인 예외: ARM64 실기기 QA를 내부 테스터 설치 이후 수행한다. latest main 빌드/정책 CI 성공 및 별도 명시적 승인일 때만 수동 Play internal release의 mode=deploy, internal_qa_approved=true, final_release_approved=false를 허용한다. 기기 QA 통과를 주장하지 않으며 폐쇄·프로덕션 자동 승격은 금지한다.
4. 비공개 테스트: 또 다른 별도 요청으로 실제 내부 완료 상태를 확인한 동일 versionCode를 앱의 정확한 비공개 트랙에 승격. 새 AAB 업로드 금지.
5. 정식 출시: 별도 명시적 승인 없이는 수행 금지.
- 위 단계의 워크플로 분리가 OPS-03~05에서 아직 구현되지 않았다면 완료라고 보고하지 말고 기존 승인 게이트를 준수한다.
- SHA·AAB 해시·applicationId·versionCode·서명 인증서·대상 트랙·Play 상태를 실제 확인한다. 실패·증빙 누락 시 중단하고 API 접수/Console 게시/테스터 설치를 구분해 보고한다.
- Secret·토큰·비밀번호·.env는 출력·커밋하지 않는다. Firebase·웹·서버 배포는 Play와 별도 승인한다.
### 5. 완료 보고
- 완료·미완료 작업, PR·SHA, 확인한 Actions URL과 상태, 병합 여부, Play 변경 여부, 다음 작업을 간결하게 기록한다.

### 앱별 보존 지침
- 마이크·음성·실시간 자막·번역 데이터의 동의·권한·개인정보 보호 정책을 보존한다. `.github/workflows/build.yml` 및 `play-internal.yml`·`play-promote.yml`의 수동 승인·정확한 버전 검증을 유지한다.

## CI-DEDUP-20261119 — 배치 종료 후 반응형 기기 QA
- PR/main은 `build.yml`의 Android 빌드, 단위·Lint, 개인정보 및 오프라인 모델 계약 검증을 유지한다.
- `responsive-qa.yml`은 PR/main에서 장시간 에뮬레이터 자동 실행하지 않으며, 최종 main SHA 고정 후 수동 1회 실행한다.
- 기존 API 35 큰 글꼴 160%, 가로 화면, 태블릿, 실제 PNG 6장과 SHA 증빙 및 실패 차단 조건을 유지한다.
- `release-build.yml`은 같은 main SHA의 Private Android APK 및 Android responsive UI QA 성공 없이는 서명 빌드를 차단한다. 마이크·음성·권한 관련 고위험 사전 검증과 실기기 QA는 별도 유지한다.


## OPS-09 — 내부 테스트 배포 1회 승인 (2026-11-19)
- 사용자가 **내부 테스트 배포**를 명시적으로 요청하면 별도의 단계별 재승인 없이 `.github/workflows/internal-release-once.yml`의 1회 승인으로 **현재 main 기본 CI 확인 → 최종 Android 전체 QA → 기존 OPS-03 서명 AAB 빌드 → 기존 OPS-04 Google Play 내부 테스트 업로드**를 순차 실행할 수 있다. 이는 이 문서의 OPS-02 제4절에서 내부 테스트용 빌드 및 게시 승인을 각각 요구한 부분의 명시적인 예외다.
- `internal_release_approved=true`, 정확한 동결 `main` SHA, 앱별 release_tag/versionCode, 안전한 QA·빌드 증빙이 필수이며 어느 단계든 누락·실패·SHA 변경이 발생하면 즉시 다음 단계를 차단한다.
- 전체 QA는 배치 종료 후 이 승인된 파이프라인에서 **앱당 1회만** 수행한다. PR·중간 병합에서 3환경 계측을 중복하지 않으며 민감 데이터 삭제·복구·권한·결제 변경의 선별 PR 검증은 유지한다.
- 새 파이프라인은 기존 OPS-03·OPS-04 검증 스크립트, 기존 서명 인증서·Play 계정, 서명 AAB SHA-256·versionCode·applicationId 및 Play 업로드 승인 문구를 변경하지 않는다. 실제 업로드 수락·Console 제공·테스터 설치 여부는 각각 구분해 보고한다.
- **비공개 테스트 승격, 정식 출시, Play 이미지/등록정보 업로드, Firebase 서버 배포는 이 승인에 포함되지 않으며 별도 승인**이 필요하다.
- 앱의 실제 Google Play 게시 작업은 사용자가 이후 내부 배포를 승인한 경우에만 실행한다. 워크플로를 추가·병합하는 작업 자체는 배포 승인이 아니다.
