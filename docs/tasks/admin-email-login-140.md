# 작업 계약: MP-140 관리자 이메일 OTP 로그인

## 목표
이메일로 일회용 코드를 받아 관리자 세션을 생성합니다. 운영 계정은 사용자가 지정한 이메일로 별도 확인·프로비저닝하고 개인 정보를 소스에 넣지 않습니다.

## 담당
- Owner: backend-foundation (서버), admin (관리자 UI/BFF)
- Reviewers: Coordinator 통합·보안 검증
- 최종 통합 담당: Coordinator

## 범위
- 포함: 관리자 전용 이메일 코드 로그인, SMTP, 영속적 제한/소비, 사용자 지정 관리자 프로비저닝 준비
- 제외: TOTP/2FA, 일반 회원 이메일 로그인 변경, 무승인 운영 배포

## 허용 경로 / 단일 작성자
- backend-foundation: 서버 auth/**, config/SecurityConfig.kt, 신규 config/AdminEmailLoginProperties.kt, Main configuration properties 등록부 필요시, src/test/**, db/migration/V42__admin_email_login.sql, http/admin-email-login.http
- Coordinator: application*.yml, build.gradle.kts, .github/**, docs/**, scripts/**, 운영 작업
- admin: 관리자 src/**, 자체 tests/**. 패키지/CI/문서는 Coordinator 소유
- 기존 코드와 새 소유 경로 충돌 시 메시지로 조정

## 공유 계약
- POST /api/v1/auth/admin-email-login/requests: {email} -> 200 {challengeId(UUID), expiresInSeconds:300, resendAfterSeconds:60}. 부적격 계정도 동일 형태의 응답, 코드/존재 여부 미노출
- POST /api/v1/auth/admin-email-login/confirm: {challengeId(UUID), code(6자리 문자열)} -> 기존 UserLoginResponse {token,refreshToken,tokenType,expiresIn,expiresAt,user}. 실패 401 동일 메시지
- 로그인 세션은 확인 완료 시에만 생성. ADMIN 및 deletedAt/authVersion을 확인 시 다시 검증
- 발송: 관리자 전용 SMTP sender. 기존 공용 SMTP 설정 재사용. 동기·시간제한 발송, 실패 시 fail closed 및 재시도. 코드 평문 영속화/로그 없음. DB에 challenge-bound HMAC만 저장
- 계정별 만료 5분, 재발송 60초, 10분 동안 확인 실패 최대5회(재발급해도 유지), 발송 최대5회/시간. 글로벌 auth IP limiter 추가 적용
- 코드 확인과 소비 및 세션 발급은 DB 잠금/트랜잭션으로 처리. 실패 횟수는 롤백되지 않음
- UI: 이메일→6자리 코드, 재발송/이메일 변경/오류/만료, 기존 비밀번호 로그인 BFF 대체. 코드와 인증 토큰 브라우저 저장소 금지
- BFF: 동일 origin/JSON 검증, no-store, 성공 응답 user만 노출, 기존 HttpOnly/Secure 쿠키 사용
- DB: V42 번호 현 원격 main 기준 확인, 기존 migration 수정 금지
- 환경변수: 관리자 OTP enable 기본 false, 운영 enable시 SMTP 및 강한 HMAC secret 검증. Coordinator가 설정/문서 반영

## 완료 조건
- [x] 정상·오류·권한·동시성 흐름 구현
- [x] 서버 전체 테스트 / PostgreSQL migration 확인 또는 미실행 이유 기록
- [x] 관리자 타입 검사·운영 빌드·브라우저 검증
- [x] OpenAPI/.http와 API 계약 일치
- [x] 개인정보/비밀값 로그 점검
- [x] 운영 대상/프로비저닝 결과 및 배포 승인 경계 기록
- [x] 인수인계

## 의존성과 위험
- 일반 이메일 OTP는 이메일 소유 확인으로서 TOTP/다중 요소 인증을 주장하지 않음
- 현재 GCP 프로젝트에서 확인되는 리소스 이름은 staging. 공개 운영 도메인과의 실제 연결을 확인하기 전 DB 변경하지 않음
- 관리자 AGENTS.md의 main 대상 PR/운영 배포 별도 승인 조항을 인계 시 명시
