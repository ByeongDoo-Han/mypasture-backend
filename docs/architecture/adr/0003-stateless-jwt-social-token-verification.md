# ADR-0003: Stateless JWT와 소셜 token 서버 검증

- 상태: Accepted
- 결정일: 2026-08-29

## 배경

모바일, 웹, 관리자 클라이언트를 공통 API로 지원하고 Google·Apple·Kakao 로그인을 제공해야 합니다. 클라이언트가 전달하는 사용자 ID나 email을 그대로 신뢰하면 계정 탈취가 가능합니다.

## 결정

API는 Spring Security의 stateless JWT를 사용합니다. 소셜 로그인 시 provider가 발급한 ID/access token을 서버가 검증하고 허용된 client/app ID와 일치할 때만 내부 사용자를 연결합니다. 비밀번호 변경·탈퇴 같은 보안 이벤트는 사용자 security version으로 기존 token을 무효화합니다.

## 대안

- 서버 HTTP session과 cookie
- 클라이언트가 전달한 소셜 profile 신뢰
- 모든 API 요청마다 OAuth provider token 직접 사용

## 결과

긍정적 결과:

- API 인스턴스에 session 상태가 없어 수평 확장이 단순합니다.
- 공급자 서명과 audience를 서버에서 확인해 위조 profile을 차단합니다.
- 모바일과 웹에서 같은 authorization 규칙을 사용합니다.

부정적 결과:

- 발급된 access token은 만료 전까지 별도 무효화 정책이 필요합니다.
- refresh token 회전·재사용 탐지와 안전한 클라이언트 저장이 중요합니다.
- OAuth client ID, redirect URI, 앱 서명 설정이 플랫폼마다 다릅니다.
