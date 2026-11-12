# QA-REAL-01-A — ARM64 오프라인 AI 번역 실기기 검증

기준 소스: `4c6dc18dd5a058703e61b12a4913510c5abeec66` (FEATURE-02~RELEASE-FREEZE-01 통합).
**현황: 최신 main 빌드 PASS · 테스트 APK 구조/체크섬 확인 · ARM64 실기기 모델 설치 및 실제 음성 번역 NOT RUN.**
ADB 장치 정보·SHA 검증, Android 에뮬레이터 캡처와 실제 음성 인식·번역은 서로 다른 검증 항목입니다.

## 빌드·테스트 APK

- GitHub Actions: https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43190289493
- 빌드 아티팩트: `hebi-live-translate-TEST-ONLY-debug`
- APK SHA-256: `f6a9ba1aecdc3dc09150dadc5dde4413469c200f0153b9c87dd36ee87308519a`
- APK 크기: `163928241` bytes / `arm64-v8a` / Android API 29 이상 / 별도 설치 ID `com.hebi.livetranslate.debug`
- 기존 Google Play `com.hebi.livetranslate`에는 영향을 주지 않으며, 모델 7개 파일 약 2.12GB는 APK에 포함되지 않습니다.

## 실기기 수동 검증 절차

1. 테스트가 허가된 ARM64 Android 10 이상 기기에 테스트 전용 Debug APK를 설치합니다. Play 내부/비공개 트랙을 수정하지 않습니다.
2. 첫 실행에서 모델 미설치 상태, 약 2.12GB의 추가 공간 안내, `시작 준비` 동작을 확인합니다(DEV-01). 기록 저장은 기본 OFF인지 확인합니다.
3. Wi-Fi와 저장 공간을 확보한 뒤 사용자 동작으로 모델 다운로드를 시작합니다. 7개 모델과 SHA-256 검증이 끝나 `준비 완료`로 표시되기 전에는 PASS로 기재하지 않습니다(DEV-02).
4. 사용자 허가로 마이크 권한과 팝업의 다른 앱 위 표시 권한을 구분하여 시험합니다. 분할 화면은 불필요한 Overlay 권한을 요구하지 않아야 합니다(DEV-07·08).
5. 영어 마이크: 소스 영어/마이크 모드에서 “The meeting starts tomorrow at three.”처럼 개인정보 없는 문장을 발화하여 실제 1차 한국어 자막 출력과 지연/누락을 기록합니다(DEV-09).
6. 일본어 마이크: 소스 일본어에서 “明日の会議は午後三時に始まります。”를 사용합니다. 정확한 한국어 표현은 실제 모델 출력으로 평가하며 예상 문구를 검증 결과로 작성하지 않습니다(DEV-09).
7. 영상 음성: 오디오 캡처가 허용되는 비식별 영상으로 재생 테스트를 진행하고 Android 화면 공유/오디오 캡처 권한을 직접 승인합니다. DRM·외부 앱 오디오 캡처 제한을 앱 오류로 오판하지 않습니다(DEV-10).
8. 일시정지/재개/종료 및 복구, 누락·중복 안내를 점검합니다. 2차 AI 교정 품질/발열은 다음 QA-REAL-01-B에서 별도 평가합니다.

## Windows PowerShell · ADB 선택적 읽기 전용 사전 검사

Android Platform Tools(ADB)와 Python 3.10 이상이 설치되고 본인이 USB 디버깅을 승인한 경우에만 실행합니다. 스크립트는 앱 설치·시작, 권한 변경, 오디오·원문·번역 내용 수집을 수행하지 않습니다.

```powershell
python scripts/qa_real_01_a_preflight.py --apk .\LiveTranscribe-QA-REAL-01-A-main-4c6dc18d-debug.apk --expected-apk-sha256 f6a9ba1aecdc3dc09150dadc5dde4413469c200f0153b9c87dd36ee87308519a --output measurements/qa-real-01-a-preflight.json
```

모델이 `준비 완료`인 이후에만 명시적으로 전체 모델 SHA 검사를 진행합니다. 2.12GB 파일 읽기로 기기에 부하가 걸릴 수 있습니다.

```powershell
python scripts/qa_real_01_a_preflight.py --verify-models --output measurements/qa-real-01-a-models.json
python -m unittest discover -s scripts -p 'test_qa_real_01_a_preflight.py' -v
```

기기 사전 검사 JSON은 Android API, ARM64 지원, 에뮬레이터 여부, 테스트 앱 설치 여부, 필요 시 모델 크기·SHA 일치 여부만 저장합니다. 기기 일련번호·원본 음성·자막 내용 및 개인 경로는 저장하지 않습니다. ADB 접근이 막히면 `BLOCKED`로 기록합니다.

## 결과 양식과 판정

기존 `python scripts/qa_real_01.py init --output measurements/qa-real-01-results.csv`를 사용해 DEV-01~15 및 CORR-01~08 총 23개 항목을 `NOT RUN`으로 시작합니다.
실제로 진행한 DEV-01·02·07·08·09·10만 기기 ABI·앱 커밋·회차·비식별 증거를 갖춰 PASS/FAIL/BLOCKED로 전환합니다. 다른 항목과 모든 CORR은 실기기 테스트 전까지 NOT RUN 상태를 유지합니다.
실제 기기가 현재 대화에 연결돼 있지 않으므로 물리 기기 모델 다운로드·영어/일본어 인식·한국어 번역 성공은 아직 검증되지 않았습니다.
Google Play 내부 테스트/AAB/스토어 이미지/Alpha 트랙 변경 금지. 최종 통합 QA와 별도 승인 시 한 번에 배포합니다.
