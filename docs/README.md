# My Pasture 서버 문서

이 색인은 문서를 **무엇을 결정하거나 확인할 때 쓰는지**에 따라 안내합니다. 각 문서의 구현·운영 상태는 작성 시점의 기록일 수 있으므로, 현재 동작을 판단할 때는 최신 코드와 배포 환경을 함께 확인합니다.

| 영역 | 내용 | 먼저 볼 문서 |
| --- | --- | --- |
| [도메인 정책](domain/README.md) | 계정, AI, QT, 알림, 보상 등의 규칙 | [인증 및 계정 정책](domain/auth-account-policy.ko.md) |
| [제품과 기능](product/README.md) | 사용자 흐름, 기능 설계, 구현 체크리스트 | [기능 체크리스트](product/feature-checklist.md) |
| [아키텍처](architecture/README.md) | arc42, C4, ADR | [arc42](architecture/arc42.md) |
| [운영](operations/README.md) | 환경 설정, 배포, 관리자 및 서비스별 운영 절차 | [환경 설정](operations/environment.md) |
| [참조](reference/README.md) | API, 오류 규칙, 데이터 및 성경 원문 출처 | [API 명세](reference/api-spec.md) |
| [검증](verification/README.md) | 테스트 방법, 시점별 결과, 점검 기록 | [로컬 부하 테스트](verification/local-load-test.md) |
| [작업 기록](tasks/README.md) | 이슈별 작업 계약, QA 및 리뷰 | [작업 기록 색인](tasks/README.md) |

## 작업별 진입점

- 사용자 흐름을 바꾸면 [제품과 기능](product/README.md)과 관련 [도메인 정책](domain/README.md)을 확인합니다.
- 시스템 경계나 API를 바꾸면 [아키텍처](architecture/README.md)와 [참조](reference/README.md)를 확인합니다.
- 실행·배포를 준비하면 [운영](operations/README.md)을, 검사 근거를 찾으면 [검증](verification/README.md)과 해당 [작업 기록](tasks/README.md)을 확인합니다.

`계획`·`부분 구현`·`구현` 같은 상태 표기는 문서별 기준을 따릅니다. 날짜가 있는 결과나 이슈 기록은 해당 시점의 근거이며 최신 운영 상태를 보증하지 않습니다.
