# UI-GAP-07-FINAL — Android 반응형 교차 검증

## 범위와 판정 원칙

이 문서는 검증 자동화의 실행 계획 및 증거 규격입니다. PR 병합이나 실행 성공 이전에는 전체 QA 완료를 의미하지 않습니다.

- GitHub Actions "UI-GAP-07 final responsive matrix"가 실제 Android 에뮬레이터 API 29, 35, 36에서 실행됩니다.
- 별도 디버그 패키지 com.hebi.livetranslate.debug를 사용하며 Play 서명, 사용자 데이터와 릴리스 트랙은 변경하지 않습니다.
- 대상 화면은 홈, 확장 설정, 빈 번역 기록, 분할 자막 미리 보기입니다. 실제 음성 모델 설치·인식·번역 동작은 포함하지 않습니다.
- Android 35는 10개 화면 환경, API 29·36은 각각 2개 대표 환경을 검사합니다. 시나리오당 실제 Android PNG 4장과 상태·소스 SHA 매니페스트를 저장합니다.
- fold-cover-light, fold-expanded-dark는 화면 크기와 밀도만 모사합니다. 실제 힌지, 접힘/펼침 전환, 제조사 UI 검증이 아닙니다.
- 자동화 성공은 앱 실행, 주요 UI 요소 접근성, 시스템 글자 배율·테마·해상도, 스크린샷 원본·SHA-256 무결성 자동 검사 성공을 의미합니다. 사람의 전수 시각 검수 결과와 혼동하지 않습니다.

## 실행 매트릭스

| API | 검증 환경 | 화면 수 |
|---|---|---:|
| 29 | phone-light, compact-dark160 | 8 |
| 35 | phone-light, phone-dark, compact-light160, compact-dark160, landscape-light, landscape-dark, tablet-light, tablet-dark, fold-cover-light, fold-expanded-dark | 40 |
| 36 | phone-dark, compact-light160 | 8 |
| 합계 | 14개 시나리오, 4개 화면 | 56 |

각 시나리오에서 Android Build.VERSION, 앱 패키지명, 시스템 라이트/다크, 폰트 배율, density, 화면 해상도, 상태별 실제 PNG 4장, 파일 중복 여부, sourceRevision을 검증합니다. 브랜치 PR과 main 병합 후의 결과를 별도 기록합니다.

## 수용 기준과 미검증 범위

1. 각 API 워크플로 성공 및 56장 PNG·매니페스트·SHA-256 기록이 확보되었는지 확인합니다.
2. 이미지 시각 검수에서 텍스트 줄바꿈, 겹침, 시스템 바 가림, 스크롤·키보드·기본 터치 영역을 별도로 검사합니다. 캡처 성공만으로 시각적 무결성을 선언하지 않습니다.
3. 실기기 WindowManager 팝업 이동·크기 조절·회전·위치 복원, 기록 실데이터 생성·검색·삭제는 미검증으로 유지합니다.
4. 접힘/펼침 중 상태 보존과 실제 폴더블 힌지·태블릿 입력 및 제조사 권한 차이는 ARM64 기기 증거가 필요합니다.
5. 모델 다운로드/무결성, 마이크·영상 음성 인식과 한국어 번역, 지연·발열·메모리는 UI-GAP-08에서 별도 검증합니다.
6. Google Play Alpha 심사와 테스터 설치 가능 여부는 UI-GAP-10/RELEASE 단계에서 확인합니다.

### 결과 판정

AUTOMATED = API별 UI 테스트·실제 Android PNG·증거 매니페스트·SHA-256이 일치합니다.
VISUAL REVIEW = 동일 조건의 캡처를 사람이 시각적으로 확인하고 발견한 문제를 수정했습니다.
PHYSICAL QA = 물리 기기에서 실제 사용 동작을 반복·기록했습니다.

자동화 결과가 FAIL이거나 시각/실기기 증거가 없다면 해당 범위를 PASS로 표시하지 않습니다.
