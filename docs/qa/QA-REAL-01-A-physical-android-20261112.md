# QA-REAL-01-A — ARM64 실기기 모델 설치·음성 번역 사전 검증

2026-11-12 기준 소스 `main` SHA: `4c6dc18dd5a058703e61b12a4913510c5abeec66`.
상태: **Android 빌드/단위 테스트 CI SUCCESS, 실제 ARM64 기기 검증 NOT RUN**.
Google Play internal/alpha 업로드 없음. `RELEASE-FREEZE-01`에 따라 최종 통합 QA 이후 별도 승인 시에만 배포.

## 1. 검증된 디버그 APK

- CI: https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43190289493
- GitHub Actions artifact: `hebi-live-translate-TEST-ONLY-debug` (Android build SUCCESS).
- 파일명: `app-debug.apk` (별도 설치 패키지 `com.hebi.livetranslate.debug`, 기존 Play 패키지 `com.hebi.livetranslate` 보존).
- APK 파일 163,928,241바이트, SHA-256: `f6a9ba1aecdc3dc09150dadc5dde4413469c200f0153b9c87dd36ee87308519a`.
- 해당 APK에는 사용자 오프라인 모델 약 2.12GB가 내장돼 있지 않습니다. 모델 다운로드·SHA 확인은 테스트 기기에서 별도 실시합니다.

## 2. Windows PowerShell / ADB 설치

Android SDK Platform Tools를 설치하고 USB 디버깅을 기기에서 허용합니다. PC에서 실행:

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
adb devices
Get-FileHash -Algorithm SHA256 .\app-debug.apk
adb install -r .\app-debug.apk
adb shell pm path com.hebi.livetranslate.debug
python scripts/qa_real_01_a_preflight.py --apk .\app-debug.apk --output measurements\device-preflight-01.json
```

APK의 해시가 위 값과 불일치하면 설치하지 않습니다. ADB가 여러 기기를 표시하면 다른 기기를 연결 해제합니다. 디버그 앱은 Play 앱과 서로 다른 패키지이므로 기존 Play 앱을 덮어쓰지 않습니다.

사전 점검은 `getprop`(SDK·ABI·에뮬레이터 신호), `df -k /data`(사용 가능한 내부 저장 공간), `pm path`(별도 테스트 앱 설치 상태)만 사용합니다. 결과 JSON은 지정한 새 경로에 생성하고 기존 파일을 덮어쓰지 않습니다.
사전 점검의 `candidateReadyForManualQA=true`는 **테스트를 시작할 조건 후보**일 뿐 실기기 진위·모델 설치·번역 성공 증명은 아닙니다.

## 3. 수동 검증 시나리오

| ID | 순서 | 통과 조건 | 현재 |
|---|---|---|---|
| DEV-01 | 최초 실행의 모델 미설치 화면 | 다운로드 필요 표시 및 시작 차단 | NOT RUN |
| DEV-02 | Wi-Fi에서 사용자가 모델 다운로드 실행 | 다운로드→SHA 검증→준비 완료(READY) 분리 확인 | NOT RUN |
| DEV-03 | 다운로드 중단·이어받기 | 기존 다운로드 손상/중복 없음 | NOT RUN |
| DEV-05 | 별도 용량 부족 상태 | 사전 안내와 실제 다운로드 차단 일치 | NOT RUN |
| DEV-07 | 마이크 권한 승인 | 명시적 승인 후 인식 재개 | NOT RUN |
| DEV-08 | 팝업 권한 승인·분할 모드 전환 | 팝업만 Overlay 권한 요구 | NOT RUN |
| DEV-09 | 비식별 영어·일본어 발화(최소 3회) | 음성 인식 후 실제 한국어 1차 번역 출력 | NOT RUN |
| DEV-10 | 사용 허가된 동영상 영어·일본어 음성 | MediaProjection 허용 후 실제 자막 출력 | NOT RUN |
| CORR-01 | 2차 교정 끄기 | 1차 번역은 그대로 표시 | NOT RUN |
| CORR-03 | 2차 교정 켜기·변경 발생(최소 3회) | 1차 선출력→의미 보존된 2차 교정 표시 | NOT RUN |
| CORR-04 | 기본 초록 1초 | 바뀐 부분만 강조 후 기존 자막색 복귀 | NOT RUN |

테스트 전 오디오/비디오 권한, 네트워크, 앱 사용 데이터는 사용자가 명시적으로 승인해야 합니다. 퍼미션 우회, 무단 오디오 수집, 자동 다운로드를 하지 않습니다.

## 4. 결과·성능 수집

```powershell
python scripts/qa_real_01.py init --output measurements\qa-real-01-results.csv
python scripts/device_perf_probe.py --package com.hebi.livetranslate.debug --duration 300 --interval 5 --output measurements\feature02-on-01.csv
python scripts/qa_real_01.py audit --results measurements\qa-real-01-results.csv --report measurements\qa-real-01-audit.json
python -m unittest scripts/test_qa_real_01_a_preflight.py
```

- DEV-01~15 및 CORR-01~08 결과는 **전부 NOT RUN**으로 시작하고 실제 측정값이 있을 때만 수정합니다.
- 모델이 '100%' 다운로드되었다고 READY로 판정하지 않습니다. 앱이 SHA 검증 후 READY로 전환한 화면과 실제 음성 번역 성공을 분리하여 확인합니다.
- 입력 원음, 개인 대화, 실명, 기기 일련번호, 전체 인식·번역문, 인증 정보를 공개 CI/PR에 업로드하지 않습니다.
- 실제 기기 접속 권한이 없는 클라우드 환경에서는 `DEV/CORR` 항목을 PASS 처리할 수 없습니다.

## 5. 완료 게이트 및 다음 단계

본 작업은 기술 준비·테스트 APK·사전 점검·프로토콜 완성으로 한정됩니다. 실기기 검증 상태는 증거 제출 전까지 NOT RUN.
실제 모델·음성 번역을 확인한 다음 `QA-REAL-01-B`에서 동일 입력·동일 기기 3회 이상의 성능/품질/발열 비교를 수행합니다.
최종 Play AAB는 모든 기능/실기기/전체 화면 QA 완료 후 별도 최종 승인에서만 1회 업로드합니다.
