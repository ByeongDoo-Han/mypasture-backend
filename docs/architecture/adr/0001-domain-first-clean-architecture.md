# ADR-0001: 도메인 우선 Clean Architecture 패키지 사용

- 상태: Accepted
- 결정일: 2026-08-29

## 배경

인증, 성경, AI, QT, 알림은 변경 이유와 보안 경계가 다릅니다. 최상위 레이어별 패키지는 한 기능을 수정할 때 여러 디렉터리를 오가게 하고 도메인 소유권을 흐리게 만듭니다.

## 결정

최상위 패키지를 도메인으로 나누고 각 도메인 아래에 `presentation`, `application`, `domain`, `infrastructure`를 둡니다. Presentation은 application을 호출하고 infrastructure는 application port를 구현합니다. Controller는 OpenAPI `*Api` interface를 구현합니다.

## 대안

- 전역 `controller/service/repository/entity` 패키지
- 기능별 패키지만 사용하고 내부 레이어를 구분하지 않음
- 초기부터 별도 Gradle module 또는 microservice로 분리

## 결과

긍정적 결과:

- 기능 변경 범위와 코드 소유권이 명확해집니다.
- 외부 공급자 adapter를 application 정책과 분리할 수 있습니다.
- 향후 충분한 이유가 생긴 도메인만 module/service로 분리할 수 있습니다.

부정적 결과:

- 작은 도메인에도 디렉터리와 port가 늘어날 수 있습니다.
- JPA entity가 다른 도메인과 직접 연관되면 경계가 흐려질 수 있습니다.
- 컴파일 단계에서 모든 레이어 규칙을 강제하지는 않으므로 review와 테스트가 필요합니다.
