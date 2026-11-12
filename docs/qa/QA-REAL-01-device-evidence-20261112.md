# QA-REAL-01 — 실기기 기능·2차 AI 교정 증거 수집

기준 main: `82ec9e94dce5ba275c3617945d1534ff9e81b475` (FEATURE-02 병합 완료).
**현황: 실기기 미실행.** 모든 새 시나리오를 NOT RUN으로 시작하고, 에뮬레이터 캡처를 모델 추론 성공 증거로 사용하지 않습니다.

## 준비
- 권한 허가된 ARM64 실제 Android 10+ 기기, 현행 모델 약 2.12GB 이상의 남은 공간과 추가 작업 공간, Wi-Fi를 준비합니다.
- Play internal v28은 FEATURE-02를 포함하지 않습니다. 기능 검증은 FEATURE-02가 있는 별도 Debug APK(`com.hebi.livetranslate.debug`) 또는 승인된 차기 테스트 빌드로 수행합니다.
- 동일 기기·같은 비식별 영어/일본어 음성·화면 밝기/충전 조건에서 교정 OFF/ON을 최소 3회 비교합니다.
- 마이크·MediaProjection·다른 앱 위 표시 권한은 테스터가 직접 승인합니다. 기록·원본 음성·기기 일련번호를 공개 로그에 저장하지 않습니다.

## 기존 DEV-01~15 및 교정 CORR-01~08
기존 DEV-01~15는 `docs/qa/UI-GAP-08-offline-device-test-plan.md`를 따릅니다.

| ID | 시나리오 | 완료 조건 |
|---|---|---|
| CORR-01 | 교정 OFF | 1차 번역 유지, 2차 색상 변경 없음 |
| CORR-02 | 교정 ON, 결과 동일 | 불필요한 교체·색상 변경 없음 |
| CORR-03 | 영어·일본어 실제 교정(3회) | 1차 번역 먼저, 같은 문장만 자연스럽게 수정, 의미·숫자·고유명사 유지 |
| CORR-04 | 기본 초록·1초 | 변경된 단어만 색상 변경 후 약 1초 내 원래 색상 복원 |
| CORR-05 | 사용자 6색·1/1.5/2초 | 저장·복원 및 라이트·다크 대비 검증 |
| CORR-06 | 새 발화·빠른 자막·종료 | 이전 문장의 늦은 결과 차단 및 교정 취소 |
| CORR-07 | 팝업·분할·스크롤 | 자막 줄바꿈·스크롤 앵커 유지·화면 겹침 없음 |
| CORR-08 | 교정 ON/OFF 성능 3회 이상 | CPU/PSS·발열·누락·지연·ANR 실측 비교 |

## Windows PowerShell · UTF-8
```powershell
python scripts/qa_real_01.py init --output measurements/qa-real-01-results.csv
python scripts/device_perf_probe.py --package com.hebi.livetranslate.debug --duration 300 --interval 5 --output measurements/feature02-on-01.csv
python scripts/qa_real_01.py audit --results measurements/qa-real-01-results.csv --report measurements/qa-real-01-audit.json
python -m unittest scripts/test_qa_real_01.py
```

- 신규 CSV는 15개 DEV + 8개 CORR, 총 23개 항목을 NOT RUN으로 생성합니다.
- DEV-09·DEV-15·CORR-03·CORR-08은 서로 다른 회차 3번 PASS가 필요합니다.
- PASS 기록에는 빌드 커밋·ABI·회차·비식별 증거 참조가 필요하며, 개인 음성·번역문 전문은 수집하지 않습니다.
- GitHub CI / 자동 에뮬레이터 캡처는 CI ONLY. `humanReviewCandidate=true`여도 실기기 성능·품질·출시 승인은 사람이 판단합니다.
- 사용자가 삭제한 자막 기록 복원이나 무단 권한 요청·Play 승격은 절대 수행하지 않습니다.

## 릴리스 보류 조건
- 실제 음성 인식·번역·교정이 확인되지 않으면 PASS로 기재하지 않습니다.
- 성능·발열·지연 악화, FAIL/BLOCKED, 증거 누락, 품질 미확인 시 Play 내부/Alpha 배포 및 마케팅용 성공 이미지 제작을 보류합니다.
- 접근 제한 Google Drive에 기기 원본 증거를 보관하고 공개 GitHub에는 비식별 판정과 측정 방법만 게시합니다.

참조: `docs/qa/7a2-device-performance.md`, `docs/qa/7a3-optimization-gates.md`, `docs/qa/FEATURE-02-ai-correction-highlight-20261112.md`.
