# 예외 처리 규칙

My Pasture 백엔드는 도메인별 업무 예외와 API 공통 오류 계약을 분리한다.

## 패키지 책임

- `{domain}.application`, `{domain}.domain`: 업무 규칙 실패를 표현하는 예외를 소유한다.
- `{domain}.presentation`: 도메인 오류 코드와 `@RestControllerAdvice`를 소유하고 HTTP 상태를 결정한다.
- `common.error`: `ApiErrorResponse`, `ErrorCode`, 입력 검증과 Spring MVC 공통 예외, 요청 추적만 담당한다.
- 도메인 예외를 `common.error`로 옮기지 않는다.
- 현재는 단일 Spring Boot 모듈이므로 별도 Gradle 공통 모듈을 만들지 않는다.

## 오류 응답

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-08-20T12:00:00Z",
  "path": "/api/v1/auth/signup",
  "traceId": "f16c0d82-7521-4a20-8d1e-44739e4a1ab3",
  "fieldErrors": [
    {
      "field": "email",
      "message": "올바른 형식의 이메일 주소여야 합니다"
    }
  ]
}
```

- 모바일과 어드민은 문구가 아니라 `code`로 분기한다.
- `message`는 사용자에게 노출해도 되는 내용만 사용한다.
- 내부 예외, SQL, 외부 API 응답, 토큰 값은 응답에 포함하지 않는다.
- 장애 문의 시 응답 헤더 `X-Request-Id` 또는 본문의 `traceId`로 서버 로그를 찾는다.

## 새 도메인 추가 순서

1. 업무 실패 예외를 해당 도메인의 application 또는 domain에 정의한다.
2. presentation에 `ErrorCode` 구현 enum을 정의한다.
3. 도메인 controller에만 적용되는 `@RestControllerAdvice(assignableTypes = [...])`를 만든다.
4. 예외별 HTTP 상태와 안정적인 오류 코드를 매핑한다.
5. MockMvc 테스트로 상태, `code`, `path`, `traceId`를 검증한다.

검증 오류, 깨진 JSON, 잘못된 파라미터 타입, 없는 경로와 예상하지 못한 서버 오류는
`GlobalExceptionHandler`가 처리하므로 도메인 advice에 중복 구현하지 않는다.
