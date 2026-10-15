# My Pasture 보안 점검 및 개선 기록

## 적용 범위

- 백엔드: Spring Security, JWT, 소셜 로그인, AI/RAG, CORS, PostgreSQL/Redis
- 모바일: React Native/Expo 세션과 묵상 초안 저장
- 관리자: Next.js 인증 게이트, HttpOnly 쿠키, 보안 헤더
- 기준일: 2026-08-20

## 문제와 해결 방법

| 중요도 | 문제 | 영향 | 적용한 해결 방법 |
|---|---|---|---|
| 긴급 | 신앙 프로필 생성·답변·결과 API가 공개되어 있고 요청의 `userId`를 신뢰함 | 타인의 검사 결과 조회·변조 가능 | JWT 사용자 ID만 사용하고 assessment/result 소유권을 매 요청 검증함 |
| 긴급 | 관리자 대시보드에 인증·권한 검사가 없음 | URL을 아는 사용자가 관리자 화면 접근 가능 | Next BFF가 백엔드 로그인과 `ADMIN` 역할을 확인하고, 서버 렌더링 때 `/auth/me`를 다시 검증함 |
| 높음 | 웹 JWT를 `localStorage`에 영구 저장 | XSS 발생 시 장기 토큰 탈취 가능 | 모바일은 SecureStore, 일반 웹은 메모리 세션, 관리자는 HttpOnly·SameSite 쿠키 사용 |
| 높음 | access token만 발급하고 로그아웃·갱신·재사용 탐지가 없음 | 탈취 토큰의 폐기와 세션 수명 관리가 어려움 | access token 15분, opaque refresh token 회전, 원문 대신 SHA-256 저장, token family 재사용 탐지, 인증 버전 폐기 적용 |
| 높음 | Google/Apple ID Token을 로그인 시도와 묶는 nonce가 없음 | 토큰 재전송·로그인 요청 혼동 위험 증가 | 서버가 5분짜리 일회성 nonce challenge를 발급하고 ID Token claim과 상수 시간 비교함 |
| 높음 | Kakao access token의 앱 식별자를 확인하지 않음 | 다른 Kakao 앱에 발급된 토큰 수용 가능 | `/v1/user/access_token_info`의 `app_id`, 만료, 사용자 ID를 확인하고 `/v2/user/me` ID와 대조함 |
| 높음 | 검증된 같은 이메일을 자동으로 기존 계정에 연결 | 계정 연결 정책 오류가 계정 탈취로 이어질 수 있음 | 기존 이메일이 있으면 자동 연결을 거부하고 기존 계정 재인증이 필요한 오류를 반환함 |
| 높음 | AI 무료 한도를 `count -> 외부 호출 -> save` 순서로 처리 | 동시 요청이 일일 제한을 초과하고 실패 요청이 누락됨 | 사용자 행 잠금 후 `PENDING` 사용량을 선점하고 성공은 `COMPLETED`, 실패는 `FAILED`로 확정함 |
| 높음 | 로그인·AI API에 서버 요청 제한이 없음 | 비밀번호 대입, 비용 폭증, 자원 고갈 가능 | Redis Lua 원자 카운터로 인증 IP와 AI 사용자별 고정 구간 제한 적용. 운영 Redis 장애 시 503으로 차단함 |
| 높음 | AI 상담 질문의 캐시 키가 단순 SHA이고 전 사용자 공유 | 질문 추측과 사용자 간 민감 답변 재사용 가능 | 사용자·모델·prompt version을 포함한 HMAC 키로 분리하고 사용량 질문 해시도 HMAC으로 변경함 |
| 높음 | OpenAI Responses 요청의 저장 정책을 명시하지 않음 | 상담 내용이 제공자 application state에 보관될 수 있음 | 모든 Responses 요청에 `store: false`를 지정함 |
| 중간 | OpenAI streaming 오류 원문을 사용자에게 전달 | 제공자 내부 메시지와 식별자 노출 가능 | 외부 원문은 폐기하고 서비스 표준 오류만 반환함 |
| 중간 | CORS 기본값이 localhost이며 credential을 허용 | 운영 오설정과 불필요한 cross-origin cookie 허용 | 기본 출처를 비우고 환경별 정확한 origin만 허용, `allowCredentials=false`, 거부 테스트 추가 |
| 중간 | PostgreSQL·Redis가 모든 호스트 인터페이스에 노출 | 로컬 네트워크에서 개발 DB 접근 가능 | 로컬 Compose의 DB/Redis를 `127.0.0.1`에만 바인딩하고 운영 Compose는 host port를 노출하지 않음 |
| 중간 | Redis 운영 암호화·인증 설정이 없음 | 캐시·rate limit 데이터 노출 또는 변조 가능 | 운영 프로필에 Redis password/TLS 필수 설정 추가. 내부 Compose는 password를 요구함 |
| 중간 | 의존성 자동 점검이 없음 | 알려진 취약점 대응 지연 | 세 저장소에 Dependabot 설정, 관리자 CI에 high 이상 audit, 모바일 CI에 critical audit 추가 |

## SQL Injection 및 요청 검증 결과

- 검색 SQL은 고정 SQL과 JPA/`JdbcTemplate` bind parameter를 사용한다.
- 사용자 입력으로 테이블명, 정렬식, SQL 조각을 조립하는 코드는 현재 확인되지 않았다.
- DTO 길이 제한과 AI use case의 300자 제한을 함께 적용한다.
- 앞으로 동적 정렬을 추가할 때는 클라이언트 문자열을 SQL에 연결하지 말고 enum allowlist로 변환한다.

## 필수 운영 환경변수

다음 값은 Secret Manager에서 주입하고 저장소에 커밋하지 않는다.

- `JWT_SECRET`: 32바이트 이상의 난수
- `AI_PRIVACY_HASH_SECRET`: 32바이트 이상의 별도 난수
- `COUPON_CODE_PEPPER`: 쿠폰 조회 HMAC 전용 32바이트 이상의 별도 난수
- `OPENAI_API_KEY`
- `DATABASE_PASSWORD`
- `REDIS_PASSWORD`, `REDIS_SSL_ENABLED=true`
- `APP_CORS_ALLOWED_ORIGINS`: 쉼표로 구분한 정확한 HTTPS origin
- `GOOGLE_OAUTH_*_CLIENT_IDS`, `APPLE_OAUTH_CLIENT_IDS`
- `KAKAO_APP_IDS`: Kakao 숫자 앱 ID
- `KAKAO_WEB_CLIENT_SECRET`: 웹 인가 코드 교환용 서버 전용 시크릿. `KAKAO_WEB_REST_API_KEY`와 정확한 HTTPS `KAKAO_WEB_REDIRECT_URI`를 함께 설정하며, 웹 빌드에는 시크릿을 넣지 않는다.

예시는 `.env.prod.example`에 있으며 실제 값은 작성하지 않는다.

## 운영 배포 체크리스트

- [ ] GCP Load Balancer 또는 Cloudflare에서 TLS를 종료하고 HTTP를 HTTPS로 리다이렉트한다.
- [ ] 백엔드와 DB/Redis에는 공개 IP 또는 공개 방화벽 규칙을 만들지 않는다.
- [ ] `SPRING_PROFILES_ACTIVE=prod`, `AI_MOCK_ENABLED=false`를 확인한다.
- [ ] Flyway V17 적용과 백업·복구 절차를 확인한다.
- [ ] Google/Apple audience와 Kakao app ID가 실제 스토어 앱 값과 일치하는지 확인한다.
- [ ] Redis rate limit 429 및 Redis 장애 503 알림을 모니터링한다.
- [ ] AI `FAILED` 사용량, 비용 급증, premium 500회 안전 한도를 모니터링한다.
- [ ] 로그에 Authorization, refresh token, OAuth token, AI 질문 원문을 남기지 않는다.

## 남은 위험과 후속 작업

1. Expo SDK 54 의존성 감사에는 high 9건, moderate 14건이 남는다. 대부분 Metro/Expo 빌드 도구 체인이며 해결 버전이 SDK 57 major이므로 별도 호환성 테스트 후 업그레이드한다.
2. 소셜 계정 회원탈퇴는 최근 5분 내 발급된 JWT를 요구하지만 제공자 재인증 토큰을 직접 검증하지 않는다. 앱 심사 전 Google/Apple/Kakao 재인증 흐름을 추가한다.
3. 관리자 access token 만료 시 자동 refresh는 아직 제공하지 않는다. 현재는 다시 로그인하며, 이후 BFF refresh route에서 회전 token을 사용하도록 확장한다.
4. JWT는 단일 HS256 secret을 사용한다. 다중 인스턴스 key rotation이 필요해지면 `kid`가 있는 비대칭 키와 이전 키 검증 기간을 도입한다.
5. Nginx 설정은 TLS가 상위 GCP/Cloudflare 계층에서 종료된다는 전제다. 직접 인터넷에 노출할 경우 인증서와 HSTS를 Nginx에 추가한다.

## 검증 항목

- 백엔드 전체 Gradle 테스트
- refresh token 회전·재사용 탐지·로그아웃 테스트
- Google/Apple nonce challenge 테스트
- 신앙 프로필 인증 및 타 사용자 접근 거부 테스트
- AI RAG 성공·실패 사용량 상태 테스트
- 허용/비허용 CORS preflight 테스트
- 모바일 TypeScript 검사
- 관리자 TypeScript, production build, `npm audit` 검사
