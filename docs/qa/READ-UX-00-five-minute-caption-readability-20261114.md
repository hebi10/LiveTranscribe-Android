# READ-UX-00 — 영상 5분 자막 지연·가독성 기준선

- 대상: `hebi10/LiveTranscribe-Android`, `com.hebi.livetranslate.debug`
- 기준: 실행한 실제 APK의 전체 Git 커밋 SHA. 문서 작성 시 마지막 확인된 `main`: `63acb2965b0eef70a98598edf7201f5ae8d509a9`
- 원칙: **실기기 측정 전에는 결과를 `NOT RUN`으로 유지**합니다. CI 빌드, 에뮬레이터 화면, 자동 분석기 출력은 물리 기기에서의 실시간 번역 성공을 증명하지 않습니다.
- 범위: 기존 STT·Hy-MT2·2차 교정, 권한, 화면·데이터, Play 워크플로, 서명키를 변경하지 않습니다. 새로운 라이브러리는 사용하지 않습니다.

## 1. 기존 지표와의 차이

- `CaptionRuntimeState`의 인식/번역 시간은 큐 대기와 계산 시간입니다. **영상 발화 시작 → 실제 자막 등장** 지연이 아닙니다.
- `scripts/device_perf_probe.py`는 CPU/PSS/배터리 온도를 수집하지만 오디오나 자막 표시 시각을 알지 못합니다.
- 이번 `scripts/read_ux_00.py`는 **동일 영상 녹화의 공통 시계**를 기준으로 사람이 주석한 숫자만 요약합니다. 음성, 인식문, 번역문, 원본 프레임, 로그캣, 기기 식별자는 읽거나 업로드하지 않습니다.

## 2. 검증 조건

1. 사용 허가를 받은 테스트용 영상, 호환 ARM64 실제 기기, 최신 검증된 `main`의 별도 설치용 Debug APK를 사용합니다. 모델 약 2.12GB의 설치·SHA 확인, 음성 권한, 영상 소리 캡처 가능 여부를 먼저 확인합니다.
2. 동일 영상·동일 기기·해상도·소스 언어·자막 설정에서 **팝업 자막 5분 × 3회**, **분할 자막 5분 × 3회**를 비교합니다. 첫 기준선은 `stableCaptions=true`, 2차 교정 ON/OFF를 반드시 별도 세션으로 기록합니다. 2차 교정 설정을 임의로 섞지 않습니다.
3. **영상의 처음 300초**를 측정하되, 마지막 발화의 자막을 기다리는 **추가 30초**까지 원본 화면과 해당 영상의 오디오가 같은 시간축에 기록된 파일을 개인 저장소에 보관합니다. 시스템/콘텐츠에 따라 화면·내부 소리 녹화가 불가능하면 `BLOCKED`로 따로 기록하고, 이를 자막 누락으로 세지 않습니다.
4. 영상 시작 시각 0ms를 기준으로 각 음성 구간의 `source_start_ms` / `source_end_ms`를 영상 편집기의 파형으로 확인하고, 해당 자막이 **처음 읽을 수 있게 된 프레임**의 `first_visible_ms`를 적습니다. 수정이 있다면 마지막 변경 프레임은 `settled_ms`, 더 이상 보이지 않는 프레임은 `hidden_ms`입니다. 같은 영상·녹화 안에서 측정하고 클록이 다른 자료를 단순히 빼지 않습니다.
5. 한 자막이 여러 발화를 합쳐 정확히 대응시킬 수 없다면 `AMBIGUOUS`로 기록합니다. 추가 30초 후에도 자막이 확인되지 않은 독립 발화만 `MISSING`으로 기록합니다. 5분 이후에도 화면에 남아 있는 자막의 `hidden_ms`는 빈 값으로 두고 표시 시간을 0으로 조작하지 않습니다.
6. 테스트 중 자막 스크롤을 조작하지 않고, 자막형/대화형 화면 배치, 원문 표시 여부, 글자 크기, 폰트 배율, 언어 선택, 교정 ON/OFF를 세션별 검증표에 고정합니다.
7. 원본 음성·자막을 포함한 녹화는 개인·비공개 공간에서만 확인합니다. 공개 PR·Actions·Drive 공유 링크에는 녹화나 대사를 올리지 않습니다. 숫자 결과만 공유할 때도 기기 일련번호·영상 제목 등 식별 정보를 포함하지 않습니다.

## 3. 계측 정의

| CSV 필드 | 정의 |
| --- | --- |
| `cue_id` | `C001` 등 비식별 구간 식별자; 한 구간/한 자막만 1:1 대응 |
| `source_start_ms`, `source_end_ms` | 원음 발화 시작·끝 시각, 영상 0~300000ms |
| `outcome` | `MATCHED` / `MISSING` / `AMBIGUOUS` |
| `first_visible_ms` | 한국어 번역 자막을 처음 읽을 수 있는 녹화 프레임 시각 |
| `settled_ms` | 1회 이상 문장 변경 시 마지막 변경 프레임 시각; 수정이 없으면 공란 가능 |
| `hidden_ms` | 자막이 화면에서 사라진 시각; 관찰되지 않았다면 공란 |
| `display_chars` | 화면에 표시된 번역문 최대 글자 수(공백 제외), 실제 대사 텍스트를 저장하지 않음 |
| `max_lines` | 해당 구간이 화면에 나타나는 동안 본 최대 줄 수 |
| `revisions` | 첫 표시 후 내용이 바뀐 횟수, 색상만 바뀌었다면 수정 횟수에 포함하지 않음 |
| `duplicates` | 같은 자막이 추가로 반복 표시된 횟수 |

- **최초 지연** = `first_visible_ms - source_start_ms`; **발화 종료 대비 지연** = `first_visible_ms - source_end_ms`. 후자는 말이 끝나기 전에 자막이 등장하면 음수가 될 수 있습니다.
- **가시 시간** = `hidden_ms - first_visible_ms`. **참고 읽기 속도(cps)** = `display_chars / (가시 시간 / 1000)`. 마지막 프레임의 자막이 남아 있는 등 `hidden_ms`가 없으면 둘 다 계산하지 않습니다.
- **안정화 지연** = `settled_ms - first_visible_ms`. 수정된 문장에만 계산합니다.
- 모든 시간 값은 **ms**, 각 5분 원본의 `first_visible_ms`/`settled_ms`/`hidden_ms`는 330000ms까지 허용합니다.
- 보고서는 median/p95/max, 누락 비율(대응 불명확 제외), 변경 빈도, 2줄 초과, 초당 12자 초과, 최초 지연 3초 초과를 제시합니다. **3초·12cps·2줄은 초기 검토 기준이며 출시 PASS/FAIL이나 보편적인 읽기 속도 기준이 아닙니다.** 사용성 테스트 후 변경합니다.

## 4. Windows PowerShell — UTF-8 실행

저장소 루트에서 실행하며 기존 `measurements` 데이터는 덮어쓰지 않습니다.

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
python -m unittest scripts/test_read_ux_00.py
python scripts/read_ux_00.py template --output measurements/read-ux-00-popup-01.csv
python scripts/device_perf_probe.py --package com.hebi.livetranslate.debug --duration 300 --interval 5 --output measurements/read-ux-00-perf-popup-01.csv
```

개인 녹화에서 5분간의 숫자만 CSV에 주석한 다음 분석합니다.

```powershell
python scripts/read_ux_00.py analyze --input measurements/read-ux-00-popup-01.csv --output measurements/read-ux-00-popup-01.json --session-id popup-en-01 --source-sha 63acb2965b0eef70a98598edf7201f5ae8d509a9 --layout popup
```

`--source-sha`는 **예시 기준 SHA**입니다. 실제 설치한 테스트 APK의 검증된 빌드 SHA로 교체해야 합니다. 다른 조건·기기를 섞은 결과를 비교하지 않습니다. 분할 자막은 별도 CSV와 `--layout split`을 사용합니다.

빈 CSV로 분석하면 `NOT_RUN`, 입력을 주석하면 `ANNOTATED_UNVERIFIED`만 출력됩니다. **테스트 PASS, 실기기 인증, 배포 승인으로 자동 판정하지 않습니다.** 최종 리뷰에서는 영상/오디오가 동기화된 원본, 기기·버전, 3회 반복 결과를 별도로 확인합니다.

## 5. 단계 완료 조건

- [x] 기존 인식·번역 처리 시간과 실제 화면 지연을 구분
- [x] 5분 원본/30초 꼬리·팝업/분할·교정 ON/OFF 기준 수립
- [x] 비식별 숫자 CSV 생성·분석 도구 및 회귀 테스트 작성
- [ ] 최신 브랜치 HEAD의 Android 및 Python CI 성공 확인·PR 병합
- [ ] 실제 ARM64 기기에서 모델 READY와 영어/일본어 영상 음성 번역 성공
- [ ] 동일 영상 최소 3회 실측 CSV·녹화 타임코드 대조
- [ ] 지연 median/p95·누락·변경·가시 시간 결과 검토

실측 근거가 없으면 `READ-UX-00`은 **측정 준비 완료 / 실측 보류**이며 종결하지 않습니다.

## 6. 후속 READ-UX-01 — 시청 집중 모드

- 실측 지연·수정·줄 수·가시 시간 분포를 근거로 **팝업 자막 우선** 시청 모드의 노출 규칙을 설계합니다.
- 실행 중 조작부 최소화, 원문 기본 숨김 유지, 자막 최대 2줄을 우선 검토하되 **긴 대사 강제 삭제·잘라내기 금지**. 긴 문장은 의미 단위 분할 또는 이전 내용 보기로 접근 가능해야 합니다.
- 자동 스크롤 위치 보존, 기존 사용자 설정/기록/모델/번역 품질 유지, 160% 확대·라이트/다크·폴더블·가로 화면 QA가 완료 조건입니다.
- 2차 AI 교정 기본값 변경 여부는 READ-UX-00에서 실제 안정화 지연과 변경 빈도를 확인한 뒤 결정합니다. 전면적인 앱 리디자인이나 새로운 OCR·클라우드 기능은 포함하지 않습니다.
- 작업을 PR로 분리하며 Google Play 내부·비공개·스토어 정보는 수정하지 않습니다.
