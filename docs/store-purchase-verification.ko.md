# 스토어 구매 검증 운영 가이드

이슈 #134. 서버 연동 코드와 로컬 자동 테스트를 추가한 단계입니다. **스토어 실구매 검증이나 결제 출시 완료가 아닙니다.** 기본값은 `BILLING_ENABLED=false`입니다.

## 구현 범위와 책임

| 계층 | 구성 | 역할 |
| --- | --- | --- |
| presentation | BillingApi / BillingWebhookApi | JWT 사용자 API, 스토어별 웹훅 계약. Controller가 구현 |
| application | BillingVerificationService / Worker | 영속 작업, 계정별 lease, 결과 반영, 승인 재시도 |
| application | BillingLedgerService | 구매별 구독 권한 및 이용권 원장 |
| infrastructure | AppleStoreGateway / OfficialAppleStoreClient | Apple 공식 서명 검증 및 현재 구독 조회 |
| infrastructure | GoogleStoreGateway / OfficialGooglePlayClient | ADC 인증, Play Developer API 조회/승인/소비 |
| infrastructure | BillingProofCipher | 구매 증거 AES-256-GCM 암호화와 HMAC 멱등 비교 |
| AI application | AiAccessPolicyResolver | 결제 활성화 시 DB에서 현재 구독 권한 판정 |

인증서/JWS 직접 구현 대신 Apple 공식 Java 라이브러리 5.2.0을 사용합니다. 유지보수 부담과 서명 검증 오류를 줄이지만 라이브러리 및 루트 인증서 갱신이 필요합니다. Google API는 기존 Google 인증 의존성과 HTTP 클라이언트를 사용해 필요한 API만 호출하며, 응답 필드와 상태 변경에 대한 회귀 테스트를 유지해야 합니다.

별도 메시지 브로커 대신 PostgreSQL 작업 테이블을 사용합니다. 현재 규모에서는 원장과 작업 상태를 함께 커밋하기 쉽습니다. 다만 polling/인덱스 비용과 단일 worker의 순차 처리량이 한계이며, 규모가 커지면 제한된 병렬 처리와 Cloud Tasks 같은 전달 계층을 검토합니다.

## 앱 연동 순서

1. My Pasture 로그인 후 `GET /api/v1/billing/account` 호출.
2. 반환된 `appAccountToken`을 Apple 구매 옵션에, `obfuscatedAccountId`를 Google 구매 옵션에 전달. 이메일이나 클라이언트 임의 ID를 넣지 않습니다.
3. 앱이 StoreKit/Play Billing으로 구매. 앱 표시 가격은 스토어 상품 조회값을 사용합니다.
4. `POST /api/v1/billing/verifications`에 requestId, store, productId, proof 전송.
5. 서버가 작업을 저장하고 202 반환. **202는 접수 성공이지 권한 지급 완료가 아닙니다.**
6. `GET /api/v1/billing/verifications/{id}`를 간격을 두고 조회.
7. COMPLETE이면 `GET /api/v1/billing/entitlements`로 실제 유효 권한 확인. Apple은 이후 StoreKit finish. Google 승인/소비는 서버가 수행.
8. 앱 종료/네트워크 재전송에는 동일 requestId 사용. 구매 복원은 SDK에서 받은 구매 증거를 다시 검증하며 수량을 직접 더하지 않습니다.

`proof`는 Apple의 transactionId 또는 Google의 purchaseToken입니다. 앱이 주장하는 구매 성공 여부, 사용자 ID, 가격, 이용권 수량, PREMIUM 값은 지급 근거로 받지 않습니다. 서버를 통해 소유권이 연결되지 않은 기존 구매는 자동 귀속시키지 않고 거절합니다.

COMPLETE는 검증 처리가 끝났다는 뜻입니다. 만료되거나 환불된 구매를 검증해도 COMPLETE가 될 수 있으므로 성공 팝업/기능 개방은 반드시 entitlements와 함께 판단합니다.

| 상태 | 의미 | 앱 처리 |
| --- | --- | --- |
| VERIFY | 검증 대기 또는 일시 실패 재시도 | 처리 중 안내, 중복 결제 유도 금지 |
| ACKNOWLEDGE | 원장 반영 후 Google 승인/소비 처리 중 | 상태 조회 지속, 결제 다시 실행하지 않음 |
| COMPLETE | 검증/승인 완료 | 권한 조회, Apple finish |
| REJECTED | 영구 검증 실패/소유권 불일치 | 고정 오류 코드와 지원 안내 |
| DEAD_LETTER | 재시도 한도 초과 | 지원 문의, 운영자 확인 |

월/연 구독은 유효기간에 기반한 AI 권한에 연결했습니다. 기존 로그인 JWT의 PREMIUM 값만 믿지 않습니다. 그러나 **3회권으로 심층 AI 세션을 실제 차감하는 흐름, 구매 화면, 앱 복원 버튼은 이번 범위에 포함하지 않았습니다.** 기존 프로필의 subscriptionTier 표시와 새 entitlements 조회도 모바일에서 함께 연결해야 합니다. 결제를 켜면 레거시 PREMIUM만 있는 계정은 무료로 판정되므로 기존 계정 전환 정책부터 확정해야 합니다.

## 검증 정책

### Apple

- 고정한 환경의 App Store Server API에서 transactionId 조회.
- 공식 SignedDataVerifier가 JWS 서명, 체인, bundle ID, 환경 및 Production appAppleId 검증.
- 상품 allowlist, appAccountToken, 구매 소유권(PURCHASED), 상품 유형 확인.
- 구독은 Get All Subscription Statuses로 **현재 상태**를 재조회. 같은 originalTransactionId의 최신 거래를 원장에 반영.
- ACTIVE 및 billing grace만 만료 시각까지 허용. 자동갱신 해지만으로 즉시 권한을 회수하지 않음.
- Family Sharing, 비갱신형 구독, 비소모성 상품, 다중 수량은 현재 지원하지 않으며 출시 상품에서 비활성화.
- 소비성 구매가 환불되면 해당 구매 이용권만 회수. StoreKit finish는 앱이 담당.

### Google

- 서버 서비스 계정의 Android Publisher 권한으로 구매 토큰 조회.
- 고정 packageName의 subscriptionsv2 또는 productsv2 API만 사용.
- 테스트 구매 여부, 상품 allowlist, obfuscatedExternalAccountId, 수량/현재 상태 검사.
- 결제 대기 상태는 권한을 지급하지 않고 재시도. 취소 구독도 만료 전이면 유지. 보류/일시중지/만료는 접근 차단.
- 주문의 갱신 회차 ID와 purchaseToken의 해시를 분리. linkedPurchaseToken 교체 시 이전 계열을 봉인해 오래된 토큰의 권한 부활 차단.
- 구독은 acknowledge, 3회권 상품은 consume. **consume은 스토어 구매 처리 완료이지 상담 3회 사용 완료가 아닙니다.**
- 현재 한 구매당 한 상품, 수량 1만 지원. 선불/할부/여러 line item 등의 상품은 별도 정책·테스트 후 개방.

구독 권한과 금전 환불 이력은 다릅니다. 구독 조회의 만료/회수 상태만으로 회계상 환불 여부를 추측하지 않습니다. 현재 구독 투영의 financialStatus는 PAID이며, 별도 환불 내역/정산 API 대사는 출시 전 추가해야 합니다.

## 환경 설정

`.env.local.example`, `.env.prod.example`에 기본 비활성 예제를 추가했습니다. 실제 환경 파일이나 키는 Git에 넣지 않습니다. GCP에서는 API와 worker에 동일한 환경/카탈로그/암호화 키를 Secret Manager와 서비스 환경 설정으로 주입합니다. **이번 작업은 Terraform, GCP 비밀값, 배포 리비전을 변경하지 않습니다.**

| 설정 | 용도 |
| --- | --- |
| BILLING_ENABLED | 전체 검증 및 DB 기반 AI 구독 판정 활성화 |
| BILLING_ENVIRONMENT | SANDBOX 또는 PRODUCTION, 서버에서 고정 |
| BILLING_ENCRYPTION_KEY | 전용 무작위 32바이트 키를 Base64 인코딩한 값 |
| BILLING_APPLE_ENABLED / BILLING_GOOGLE_ENABLED | 준비된 스토어만 활성화 |
| BILLING_APPLE_BUNDLE_ID / APP_ID | 앱 bundle ID 및 숫자 App Store 앱 ID |
| BILLING_APPLE_ISSUER_ID / KEY_ID | App Store Server API 인증 식별자 |
| BILLING_APPLE_PRIVATE_KEY_PATH | 서버에 마운트한 In-App Purchase API .p8 키 경로 |
| BILLING_APPLE_ROOT_CERTIFICATE_PATHS | 공식 Apple 루트 인증서 파일 경로 목록, 쉼표 구분 |
| BILLING_GOOGLE_PACKAGE_NAME | Play 등록 패키지명 |
| BILLING_GOOGLE_PUSH_AUDIENCE | Pub/Sub push OIDC audience, 정확한 HTTPS 수신 URL |
| BILLING_GOOGLE_PUSH_SERVICE_ACCOUNT | 승인한 Pub/Sub push 서비스 계정 이메일 |
| BILLING_{APPLE,GOOGLE}_{MONTHLY,YEARLY,DEEP}_PRODUCT_ID | 실제 스토어 상품 ID |
| APP_WORKER_ENABLED | worker에서 true. false인 API만 있으면 검증이 대기 상태로 남음 |

Apple API 키는 EAS 앱 업로드용 키와 역할을 구분합니다. App Store Connect의 In-App Purchase 키와 공식 Apple PKI 인증서를 준비합니다. Production appAppleId를 Team ID나 bundle ID로 대체하면 안 됩니다.

Google은 Android Publisher API를 활성화하고 Play Console에서 서비스 계정에 대상 앱의 구매 조회/승인에 필요한 최소 권한을 부여합니다. Cloud Run 실행 서비스 계정의 ADC를 사용합니다. 로컬에서는 ADC와 서비스 계정 가장(impersonation) 권한을 준비합니다. `google-services.json`은 모바일 Firebase 설정이며 결제 서버 인증키가 아닙니다. Pub/Sub 발신 계정과 Developer API 접근 계정을 구분합니다.

Cloud Run worker는 요청이 없을 때도 CPU와 실행 수명이 보장되는 구성인지 확인해야 합니다. 최소 인스턴스만 지정한 요청 기반 CPU 설정에서 @Scheduled 실행을 보장한다고 가정하지 않습니다.

Sandbox/TestFlight 구매는 별도 Sandbox 서버/DB로 테스트합니다. Production 서버가 임의로 Sandbox에 fallback하거나 테스트 구매에 운영 권한을 부여하지 않습니다. 실제 심사 빌드의 Sandbox 구매 경로는 출시 전 별도로 설계·확인해야 합니다.

## 웹훅과 재조회

- Apple Notifications V2: `POST /api/v1/billing/webhooks/apple`. signedPayload 및 내부 거래 JWS 검증.
- Google Pub/Sub RTDN: `POST /api/v1/billing/webhooks/google`. OIDC 서명, issuer, audience, email, email_verified 확인.
- 앱 JWT 인증을 면제한 URL이지만 **인증 없는 지급 URL이 아닙니다.** 각 스토어의 별도 검증을 수행합니다.
- Apple notificationUUID / Google messageId를 저장 요청의 멱등 ID로 사용. 알림 내용만으로 권한을 수정하지 않고 worker가 다시 조회.
- Google 알림에는 우리 계정이 없어 최초 API 조회로 소유자를 식별한 뒤 영속 작업 접수. 이 조회/DB 실패 시 2xx를 반환하지 않아 Pub/Sub가 재시도하도록 함.
- TEST 알림은 인증 확인만 하고 지급하지 않음.
- 미등록 계정/잘못된 상품/위조 알림은 성공으로 숨기지 않음. Pub/Sub dead-letter topic과 Apple 알림 실패 확인을 운영에 연결해야 함.
- 알림 유실 보정: 구독 6시간, 소비성 구매 24시간마다 현재 상태 재조회. 같은 증거·상품의 완료 작업은 하나만 정기 조회 담당.
- 알림 지연 또는 스토어 장애 동안 이미 확인한 구독은 DB 만료 시각까지 유지. 알 수 없는 신규 구매에는 권한을 지급하지 않음.

## 동시성과 장애 복구

사용자 행 잠금 안에서 lease와 증가하는 revision을 예약하고 잠금을 해제한 뒤 스토어 API를 호출합니다. 외부 호출 동안 DB 트랜잭션을 유지하지 않습니다. 결과 반영 시 lease 소유자/만료/revision을 다시 확인하여 늦은 작업을 무시합니다.

원장·계열 교체·작업 상태는 같은 트랜잭션으로 커밋합니다. Google 승인/소비는 이후 단계이므로 그 호출이 실패해도 이용권을 다시 지급하지 않고 승인만 재시도합니다. 기본 lease 300초, 최대 10회, 지수 backoff 최대 1시간입니다. Developer API 요청 timeout 30초이며 Apple API도 공식 클라이언트의 timeout을 사용합니다.

구매 증거는 전용 AES-GCM 키와 작업 UUID를 AAD로 저장합니다. 공개 응답에는 작업 상태/오류 코드만 포함하고 구매 토큰/JWS/API 비밀키는 노출하지 않습니다. requestId가 같고 내용이 다르면 409입니다. 사용자별 신규 요청은 하루 30개로 제한하고 별도 Redis HTTP 제한도 적용합니다. 웹훅은 edge 제한/인증/재전송을 함께 고려해야 합니다.

현재 암호화 형식에는 key version이 없어 기존 키를 단순 교체하면 과거 작업을 해독하지 못합니다. **키 순환 도구, 구매 증거 보존기간과 파기, 장기 실패 작업의 재처리 관리자 화면, dead-letter 알림은 출시 전 필수 후속 작업**입니다. 정기 재조회가 오래된 소비성 구매에도 계속되므로 실제 사용자 수에 맞춘 보존/조회 종료 정책 없이 활성화하지 않습니다.

## 데이터베이스

V42 원장에 이어 V43이 nullable source_key와 계정/검증 작업/구매 계열 테이블을 추가합니다. 기존 마이그레이션 파일은 변경하지 않습니다. 운영 적용 전 백업과 실제 데이터 복사본의 마이그레이션 검증을 별도로 수행합니다.

| 테이블 | 용도 |
| --- | --- |
| billing_accounts | 가명 스토어 계정 토큰, 사용자별 lease/revision |
| billing_verification_jobs | 암호화 구매 증거, 멱등 요청, 재시도/승인/정기 조회 |
| billing_sources | 구매 계열의 소유자와 교체 관계 |
| billing_purchases | 현재 권한, 거래별 지급 총량 |
| billing_purchase_events | 반영/무시된 검증 이력 |
| billing_credit_reservations | 서비스 내 이용권 사용 이력 |

운영 점검은 원문 증거를 조회하지 않고 phase별 건수, 가장 오래된 미처리 시각, attempts, last_error, 만료 lease를 봅니다. VERIFIED라는 내부 DTO 이름 자체가 암호학적 검증을 의미하지 않으므로 컨트롤러에서 해당 DTO를 직접 입력받지 않습니다.

## 검증과 출시 체크리스트

로컬 자동 검증:
- `./gradlew test --no-daemon`: 스토어 fixture/암호화/위조 거절/권한/전체 회귀.
- `./gradlew billingPostgresTest --no-daemon`: 고정한 폐기용 PostgreSQL에서 동시성 및 V42/V43 마이그레이션.
- `http/billing-verification.http`: 실제 테스트 상품/계정이 준비된 별도 서버용 흐름. 변수를 private 파일에 보관.

실제 스토어 호출 없이 자동 테스트합니다. 통과 여부/개수는 작업 기록에 남깁니다. 위조 JWS 거절 테스트는 실제 Apple Sandbox 성공 결제를 대체하지 않습니다.

출시 전 별도 검증:
- [ ] Apple Sandbox / Google 라이선스 테스터의 구매·갱신·해지·환불·복원·pending·승인 실패
- [ ] 실제 Apple 인증서/JWS 및 Pub/Sub OIDC 성공/실패, Test notification 수신
- [ ] 실제 앱의 appAccountToken/obfuscatedAccountId 전달과 재설치·다른 앱 계정 복원 정책
- [ ] TestFlight/스토어 심사 환경과 운영 권한 분리
- [ ] 유료 AI 세션 성공 시 이용권 차감, 실패 시 복원
- [ ] 관리자 재처리와 알림, 증거 보존/파기, 키 순환
- [ ] Voided Purchases/환불 내역 대사 및 API에서 더 이상 조회되지 않는 오래된 거래 처리
- [ ] 실제 상품별 업그레이드·다운그레이드·유예 정책 및 레거시 PREMIUM 계정 전환
- [ ] Cloud Run worker 상시 실행 조건/최대 인스턴스/API 쿼터/관측 설정

공식 참고: [Apple Server Library](https://github.com/apple/app-store-server-library-java), [Google 구매 보안](https://developer.android.com/google/play/billing/security), [구독 조회](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2), [일회성 구매 조회](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2), [RTDN](https://developer.android.com/google/play/billing/rtdn-reference).
