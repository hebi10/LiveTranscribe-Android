# QA-REAL-01-B — ARM64 실기기 3회 반복 성능·자막 검증

작성: 2026-11-16 (KST)
기준 소스: hebi10/LiveTranscribe-Android / main 752aded70389ca3b3d7ec7f32432756b0193096e

**상태: 실기기 모델 설치·실제 번역·발열 측정 NOT RUN.**
이 작업은 비식별 측정 자료 분석기·테스트 코드·수동 인계 문서를 추가합니다. 앱 기능, 모델, 서명, 사용자 데이터, Google Play 트랙은 변경하지 않습니다.

## 기존 확인과 미확인 구분

- main APK 빌드·Kotlin·Lint·정책 검증: https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43789634287
- API 29·35·36 Android 에뮬레이터 UI: https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43789634357
- 집중 모드 통합·합성 자막 QA: https://github.com/hebi10/LiveTranscribe-Android/pull/89
- 이들은 **Qwen3-ASR 및 Hy-MT2 모델이 실제 ARM64 휴대폰에서 번역에 성공한 증거가 아닙니다.**
- Google Play 기존 internal v28과 최신 집중 모드의 빌드는 다릅니다. 실기기 시험은 별도 설치 패키지 com.hebi.livetranslate.debug를 사용합니다.

## 실기기 사전 조건

1. Android 10 이상, arm64-v8a 실제 기기와 충분한 저장 공간(약 2.12GB 모델 + 설치 여유 공간), Wi-Fi.
2. 현재 main의 https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43789634287 에서 hebi-live-translate-TEST-ONLY-debug 아티팩트의 app-debug.apk 확보 및 신뢰할 수 있는 APK 해시 확인.
3. 모델 다운로드·마이크·MediaProjection·Overlay 권한은 기기 사용자가 직접 확인·승인. 권한을 자동 부여하거나 DRM 보호 영상의 캡처 제한을 우회하지 않음.
4. 다운로드 100%는 READY가 아님. 앱의 SHA 검증 후 READY를 확인하고, 실제 음성→1차 한국어 자막 출력도 별도로 확인.
5. 원음·동영상·전체 번역문·기기 일련번호·인증정보를 GitHub 및 공개 Drive에 업로드하지 않음.

## 동일 기기·동일 영상 9회 비교

각 묶음은 기기, 영상 파일(비식별 ID), 입력 소스, 언어, 화면 배치, 앱 SHA를 모두 동일하게 고정합니다.

| condition | 집중 모드 | 2차 교정 저장 선호 | 확인 항목 | 반복 |
| --- | --- | --- | --- | --- |
| correction_off | OFF | OFF | 1차 번역만 | 3회 |
| correction_on | OFF | ON | 1차 번역 후 교정 또는 동일 결과 | 3회 |
| focus_on | ON | ON | 선호 보존, 2차 교정 건너뜀 | 3회 |

첫 시험은 영어 동영상 음성 + 팝업으로 진행합니다. 일본어, 마이크, 분할 화면은 **별도의 묶음**으로 반복하며 서로 다른 조건의 수치는 합치지 않습니다.
각 5분 영상의 말미 자막을 기다리는 추가 30초를 포함하여 영상·스피커 음성과 자막이 공통 시간축에 담긴 자료를 비공개 보관합니다. MediaProjection 충돌 시 외부 카메라로 화면과 소리를 함께 촬영합니다. 2차 교정이 ON이라도 결과가 동일하면 품질 개선이라 주장하지 않습니다.

## Windows PowerShell / UTF-8 실행

먼저 저장소 루트에서 최신 debug APK를 준비하고 실제 연결 기기를 확인합니다. 아래 명령은 모델 다운로드, 오디오 입력 또는 권한 허용을 자동화하지 않습니다.

~~~powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$sha = "752aded70389ca3b3d7ec7f32432756b0193096e"
adb devices
Get-FileHash -Algorithm SHA256 .\app-debug.apk
adb install -r .\app-debug.apk
python scripts/qa_real_01_a_preflight.py --apk .\app-debug.apk --output measurements\qa-real-01-b\preflight.json
python scripts/qa_real_01.py init --output measurements\qa-real-01-b\scenario-results.csv
python scripts/qa_real_01_b.py init --output measurements\qa-real-01-b\sessions.csv
~~~

실제 시험에서 처음 조건(correction_off-1, 영어/동영상/팝업)을 실행하면서 수치를 수집합니다. 화면에 나온 자막의 첫 표시 시각과 누락/변경 시각은 허가된 영상 녹화에서 사람이 확인해 숫자로만 기록합니다.

~~~powershell
python scripts/read_ux_00.py template --output measurements/qa-real-01-b/correction_off-1-events.csv
python scripts/device_perf_probe.py --package com.hebi.livetranslate.debug --duration 300 --interval 5 --output measurements/qa-real-01-b/correction_off-1-perf.csv
python scripts/read_ux_00.py analyze --input measurements/qa-real-01-b/correction_off-1-events.csv --output measurements/qa-real-01-b/correction_off-1-caption.json --session-id correction_off-1 --source-sha $sha --layout popup
~~~

9회 모두 동일한 명령의 세션 ID와 출력명을 변경해 실행합니다. 각 회차에 고유한 비공개 녹화 증빙, 성능 CSV 및 자막 측정 JSON이 필요하며 파일·파일 내용·증빙 참조를 재사용할 수 없습니다.
sessions.csv의 각 PASS 행에는 다음을 수동 작성합니다: source_sha, device_alias(예: DEVICE_A), clip_id(예: CLIP_A), layout=popup, input_mode=video, source_language=en, model_ready=YES, first_translation=YES, correction_behavior, performance_csv, caption_json, evidence_reference.
performance_csv와 caption_json은 sessions.csv가 있는 폴더 안의 상대 경로로 기재하며, correction_behavior는 OFF / ON_CHANGED / ON_UNCHANGED / FOCUS_SKIPPED 중 실제 확인한 값만 기록합니다.

~~~powershell
python scripts/qa_real_01_b.py analyze --input measurements/qa-real-01-b/sessions.csv --output measurements/qa-real-01-b/comparison.json
python scripts/qa_real_01.py audit --results measurements/qa-real-01-b/scenario-results.csv --report measurements/qa-real-01-b/scenario-audit.json
~~~

검증기는 9회 반복 및 소스/기기/입력/화면 조건 일치, 실제 수치가 있는 CPU·PSS 30개 이상, 270~330초의 5분 계측 구간, 읽기 결과 JSON 출처와 세션 일치를 확인합니다. 조건별 CPU/PSS·배터리 온도·최초 자막 표시 p95·누락률을 요약합니다.
보고서에는 항상 physicalDeviceProven=false, releaseApproved=false가 기재됩니다. CSV 자체는 물리 기기와 AI 번역의 성공을 증명할 수 없기 때문에 **독립적인 원본 자료 확인이 필수**입니다.

## 종료 조건 및 후속

- 기존 DEV-01~15 및 CORR-01~08 총 23개 시나리오는 별도 검수: https://github.com/hebi10/LiveTranscribe-Android/blob/main/docs/qa/QA-REAL-01-device-evidence-20261112.md
- 이 신규 9회 성능 비교가 기존 23개 기능 검사를 대체하지는 않습니다.
- 실제 영어·일본어 마이크/동영상 번역, 저장·권한 복구, 원문·자막 표시, 교정/강조, 종료, CPU·PSS·발열·누락을 실기기에서 확인합니다.
- 실기기 원본 증거 확보 전 모든 DEV/CORR/QA-REAL-01-B 결과는 NOT RUN. 최종 테스트 승인, Play internal 및 closed 승격 금지.
- 실측 결과를 확보한 후에만 READ-UX-04의 번역 지연·가독성 튜닝 범위를 정합니다.
