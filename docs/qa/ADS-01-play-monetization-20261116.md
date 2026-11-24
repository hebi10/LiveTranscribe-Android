# ADS-01 — LiveTranscribe AdMob·구독 코드 및 출시 게이트 (2026-11-16)

## 확정 범위
- 패키지: `com.hebi.livetranslate`, 기존 서명자·오디오·기록·오프라인 모델·팝업·버블·분할 모드 보존
- 광고: 홈 화면 하단 배너 1개(동영상/번역 실행 중, 오버레이, 캡처 권한·모델 준비 화면에는 표시하지 않음)
- 결제: Google Play 정기 결제 상품 `remove_ads`, 기본 요금제 `monthly`, `yearly`. **목표 한국 가격** 월 1,000원·연 9,900원. 실제 금액은 Console에서만 설정.
- 결제는 Google Play UI를 사용. 구매 내역이 있더라도 백엔드 subscriptionsv2 검증을 통과하기 전에는 광고 제거 권한을 부여하지 않음. 결제 검증 불가 시 UNKNOWN이며 광고는 표시하지 않음.
- 신규 SDK: Google Mobile Ads Next-Gen 1.6.0, UMP 4.0.0, Google Play Billing 9.1.0. Python 표준 라이브러리 기반 운영 검증기 초안은 별도 배포 전용으로 보관.

## 운영에 필요한 값
- `GMA_APP_ID` (AdMob **앱 ID**, 형식 ca-app-pub-...~...)
- `GMA_BANNER_UNIT_ID` (홈 배너 광고 단위, 형식 ca-app-pub-.../...)
- `BILLING_VERIFIER_URL` (기기 요청을 인증·제한하는 공개 HTTPS 게이트웨이의 /verify 경로; 보호된 내부 검증기가 Google Play Publisher API로 유효성 확인)
- 광고 ID가 없으면 운영 광고 OFF, 검증 URL 또는 보호된 GET `/verify` 준비 확인이 없으면 구매 버튼 OFF. 공개 테스트 ID가 실제 광고 요청에 사용되지 않도록 릴리스 구성 검증.
- 디버그 공식 테스트 광고는 요청 시에만 `-PenableTestAds=true`; 실기기 테스트에는 테스트 광고만 사용.
- 상품 생성은 Billing SDK 포함 AAB를 내부 테스트에 먼저 배포한 후 Console에서 구독·기본 요금제를 등록하고 활성화.

## Cloud Run 연결

- `server/`를 빌드 컨텍스트로 사용합니다. 컨테이너는 Cloud Run의 `PORT`에서 수신하며 `/healthz`와 `/verify`를 제공합니다.
- Cloud Run에는 이 앱의 구독 조회에 필요한 권한만 부여한 런타임 서비스 계정을 지정합니다. 검증기는 인스턴스당 60초에 최대 120건의 Play 검증을 허용하므로 초기 검증 시 최대 인스턴스 수를 1로 설정하고 429 응답을 모니터링합니다.
- LT-FIX-07/08: 검증기는 직접 공개하지 않고 IAM 보호된 Cloud Run 서비스로 배포합니다. 신뢰된 게이트웨이가 앱 요청의 인증·무결성 검증(예: Play Integrity 연동) 및 호출자별 속도 제한을 담당하고, 기존 요청에 포함된 임의 내부 인증 헤더를 제거한 뒤 서버 간 인증 증명을 주입해야 합니다. LT-BILLING-01에서는 Android 표준 Play Integrity 요청 및 공개 게이트웨이 코드를 구현합니다. Cloud 프로젝트/Play Console 연결, Cloud Run IAM 설정, 실제 라이선스 기기 및 운영 요청 제한 검증은 별도 승인 후 수행합니다.
- 내부 검증기의 `BILLING_GATEWAY_AUTH_TOKEN`은 최소 32자 이상 서버 전용 Secret Manager 값으로 설정합니다. APK, Gradle, 저장소, 클라이언트 요청에는 포함하지 않습니다. 누락 시 503, 불일치 시 403이며 Play API 호출 전 차단합니다. Cloud Run IAM, ingress 제한, 최대 인스턴스 및 동시성 제한을 함께 적용합니다.
- 게이트웨이는 GET /verify 준비 확인과 POST /verify 구매 검증을 전달합니다. GET 200 및 올바른 상품 ID/ready=true가 확인되기 전에는 구매 UI가 비활성화됩니다. 이 확인은 게이트웨이 연결 확인일 뿐 Play API 실구매 성공을 증명하지 않습니다.
- 서버는 요청 2KB, Google 응답 최대 크기, 읽기 시간 및 동시 Play 검증 8건을 제한하고 기존 분당 120건 한도를 유지합니다. Android 역시 응답 2048바이트 초과 또는 Boolean이 아닌 active 값을 거부합니다.
- 게이트웨이의 HTTPS /verify URL과 Play Integrity에 연결된 공개 Cloud 프로젝트 번호를 GitHub google-play 환경의 BILLING_VERIFIER_URL, PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER 변수로 설정합니다. 서명 AAB 빌드가 이 값을 Gradle에 전달합니다. 게이트웨이 검증·실제 구매 테스트 및 현재 Console 확인 전에는 새 서버 또는 구매 UI의 운영 준비를 완료로 기록하지 않습니다.

## 광고 정책 및 개인정보 검증
1. UMP 동의 갱신 후 `canRequestAds()`가 허용이고, Play 구독 확인 결과가 INACTIVE일 때만 홈에서 광고 요청.
2. ACTIVE/PENDING/UNKNOWN 구독 또는 녹음·인식·번역 중에는 광고 없음.
3. 광고/결제 SDK는 `monetization` 폴더로 격리. 음성, 번역문·원문, 음성 파일, 기록이 SDK 또는 검증 API로 전달되지 않는지 코드·실기기 네트워크에서 검증.
4. 앱 화면 개인정보 문구, `docs/privacy-policy.html`, Play Console 광고 신고 및 Data Safety가 일치해야 함. 개인정보 페이지의 GitHub Pages 자동 배포를 제거해 **별도 수동 승인**으로 전환.
5. 라이선스 테스터만 실제 구독 결제·갱신·취소·환불·권한 복원 시험. 자동화는 실제 결제 시도하지 않음.

## 미완료 운영 및 릴리스 게이트
- [ ] AdMob에서 앱 ID·홈 배너 단위 확인 및 UMP 메시지/동의·지역 설정
- [ ] 백엔드 HTTPS 도메인·인증·서비스 계정 권한·rate-limit/Play Integrity 등 서버 운영 보호책 점검 후 배포
- [ ] Billing SDK 포함 서명 AAB 수동 내부 테스트 배포, Play Console에서 `remove_ads` 상품 등록 및 base plan `monthly`·`yearly` 한국 가격 입력
- [ ] Play Data Safety 및 개인정보처리방침 게시를 운영 변경에 맞춰 수동 승인
- [ ] 실제 ARM64 기기에서 광고 동의/거부, 정상 라이선스 구매, 취소/복원, 환불, 앱 재시작 시 광고 권한, 오프라인 번역 무영향 확인
- [ ] 전체 Android CI·OPS-07/08·실기기 검증 후 별도 승인으로 다음 내부 테스트 게시. 비공개/프로덕션 미승인.
