# RELEASE-FREEZE-01 — Google Play 최종 일괄 배포 원칙

기준 main: `82ec9e94dce5ba275c3617945d1534ff9e81b475` (FEATURE-02 코드 병합).
승인: 기획 작업을 PR 단위로 진행·검증·순차 병합하되 **개별 PR마다 Play에 올리지 않고 전체 작업·최종 QA가 완료된 후 한 번에 배포**합니다.

## 배포 동결 범위

- `.github/workflows/play-internal.yml`: Android `Private Android APK` 완료 이벤트에서 자동 실행되는 Play 내부 테스트 출시 경로 제거. 수동 `workflow_dispatch`에서만 시작합니다.
- 수동 `mode=deploy` 또는 `mode=initialize`는 새 `final_release_approved=true` 확인을 요구합니다. 기본값은 `false`이며 미승인 시 서명·업로드 전에 실패합니다.
- `.github/workflows/play-drive-assets.yml`: 자동 이미지 교체 `workflow_run` 트리거 제거, 수동 `check/apply`만 유지하고 `apply`에는 최종 승인 입력을 요구합니다.
- 이전 회차의 Alpha 승인·초안·정산·UI-GAP-closed 네 워크플로는 시작 잡을 `if: false`로 고정해 자동 Play 편집/승격을 실행할 수 없습니다.
- 기존 `play-promote.yml`, `play-listing.yml`, `play-store-01.yml`은 수동 실행 전용이며, `promote`/`apply` 실행에도 `final_release_approved=true`를 요구합니다. `play-drive-assets.yml`의 `apply`도 같은 승인이 필요합니다. 개발 중에는 **적용·승격 모드 실행 금지**.
- `.github/workflows/build.yml`의 일반 Android 빌드·계측·캡처는 계속 수행하며 Play 트랙·버전 코드는 변경하지 않습니다.

## 구현 및 검증 병합 순서

1. 이미 병합: FEATURE-02 PR #70 — 로컬 AI 2차 교정 및 수정된 단어 기본 초록색·1초 강조.
2. 검증 중: COMPAT-01 PR #71 — 실제 모델 다운로드 크기 기반 저장 공간 안내.
3. 검증 중: QA-REAL-01 PR #72 — 15 DEV + 8 CORR 실기기 증빙·검수 도구.
4. 검증 중: FEATURE-02-QA-02 PR #73 — 네이티브 변경 단어 강조·만료·색상 6종 대비 실기기 계측 대상.
5. 추가 구현은 별도 기능 PR → 동일 SHA에서 빌드/단위/Lint/Android 화면 QA → 문제 수정 → 순차 안전 병합. Play API 배포 없음.
6. 계획에만 있는 OCR·화면/음성 통합·양방향 번역(PLAN-EXT-01)은 **확장 범위 채택 미확정**입니다. 기능 구현은 별도 명시적 채택 시에만 진행합니다.

## 최종 릴리스 전 필수 게이트

- 합의한 필수 작업 PR 전부 merged, `main` 동일 SHA 기준 Android 빌드/계측 API29/35/36, 폰·태블릿·160% 라이트/다크 원본 캡처 성공.
- 호환 ARM64 실제 기기에서 모델 약 2.12GB 다운로드 및 SHA 확인, 마이크/동영상 영어·일본어 음성 번역 실제 성공 증빙.
- FEATURE-02 추가 교정: 동일 문장 의미 보존, 1차 번역 선출력, 변경 부분만 1초 강조·원색 복원, 색상 6종, 스크롤 보존, 교정 취소·오류 시 1차 유지.
- `QA-REAL-01` DEV-01~15·CORR-01~08의 실행 결과를 기록하고 3회 반복 항목을 완료. CPU·PSS·온도·지연·누락·ANR 기준의 실제 측정값 확보.
- 테스트용 음성·전체 번역문·개인정보를 공개 CI 로그/아티팩트에 업로드하지 않음.
- 스토어 문구·아이콘·대표 이미지·이미지 8장과 신고/필수 시연 영상·Google Play 정책/게시 상태 최종 검수.
- 최종 릴리스 커밋·앱 서명 SHA·AAB 16KB 검사·Play API internal 최신 코드 확인·재조회.

## 승인된 최종 일괄 배포 절차

1. 위 게이트 통과 후 사용자에게 변경 목록과 최종 `main` SHA 및 AAB 버전 제안.
2. 별도 **최종 릴리스 명시 승인** 시 `Play internal release`를 `main`, `mode=deploy`, `final_release_approved=true`로 1회 수동 실행.
3. Google Play API 독립 재조회에서 등록 상태·서명/버전 확인 후 내부 테스터 설치 확인.
4. 비공개 테스트 승격·심사 제출은 내부 설치·서비스 신고 요건 확인 및 별도 사용자 승인 후 진행.

**주의:** 이 문서는 Play 배포 승인이나 실기기 테스트 성공 보고서가 아닙니다. 지금까지 실제 Play 내부 테스트는 `1.3.1 (28)`이고 모든 신규 기능은 Play 앱에 아직 반영되지 않았습니다.
