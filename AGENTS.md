# My Pasture Backend Agent Rules

이 규칙은 `mypasture-backend` 저장소 전체에 적용됩니다.

## 도메인 소유권

| 역할 | 기본 소유 패키지 |
| --- | --- |
| Backend Foundation | `auth`, `user`, `config` |
| Scripture Intelligence | `bible`, `reading`, `ai`, `daily_word` |
| Community | `quiet_time`, `attendance`, `notification`, `faith_profile` |

작업 계약에 다른 Owner가 명시되지 않으면 위 표를 따릅니다.

## 공유 경로

다음 경로는 Coordinator가 단일 작성자를 지정해야 합니다.

- `src/main/resources/db/migration/**`
- `src/main/resources/application*.yml`
- `build.gradle.kts`, `Dockerfile`, `docker-compose.yml`
- `http/**`
- 여러 도메인이 사용하는 공통 DTO와 Security 설정

## 아키텍처 규칙

- 도메인 패키지 아래 `presentation`, `application`, `domain`, `infrastructure` 책임을 유지합니다.
- Presentation은 HTTP 변환, Application은 use case, Domain은 규칙, Infrastructure는 외부 연동을 담당합니다.
- Swagger 계약은 `*Api` interface에 두고 Controller가 구현합니다.
- Entity를 API 응답으로 직접 노출하지 않습니다.
- 생성자 주입과 Repository interface를 사용합니다.
- 클래스에는 역할을 설명하는 짧은 한글 KDoc을 작성합니다.

## 데이터와 동시성

- 기존 Flyway migration은 수정하지 않습니다.
- migration 번호를 만들기 전에 다른 작업과 충돌 여부를 확인합니다.
- 중복 가입, 참여, 출석, 보상 지급은 DB unique constraint 또는 동등한 영속적 멱등성을 가집니다.
- Redis lock만으로 영구적인 중복 방지를 주장하지 않습니다.
- H2 테스트를 통과해도 PostgreSQL/Flyway 영향이 있으면 Docker 환경에서 추가 확인합니다.

## API와 보안

- API 변경 시 OpenAPI interface와 `http/*.http`를 함께 수정합니다.
- 인증과 객체 소유권을 서버에서 검증합니다.
- 토큰, 비밀번호, OAuth code, 개인정보를 로그에 남기지 않습니다.
- 운영 환경변수에는 안전하지 않은 기본값을 두지 않습니다.
- AI 요청은 입력/출력 제한, RAG 근거, 비용 기록, timeout/retry/rate limit 정책을 지킵니다.

## 변경 범위별 검증

모든 변경에서 `git diff --check`를 실행합니다. 다음 위험도 기준으로 로컬 검사 범위를 정하며 최종 전체 CI는 유지합니다.

### 검증 위험도와 로컬 전체 검사

- 작업 규모와 검증 위험도는 별도로 판단합니다. 파일 수나 단순 기능 추가라는 이름만으로 저위험으로 분류하지 않습니다. 영향받는 저장소의 검사 범위를 정합니다.
- 다음 조건을 모두 만족하면 저위험이며 로컬 전체 테스트는 기본적으로 생략합니다: 기능 경계와 호출자가 명확하고, 담당·인접 회귀로 영향을 설명할 수 있으며, 아래 고위험 변경이나 미해결 회귀 우려가 없습니다. 신규 기능/API도 이 조건을 만족할 수 있습니다. Lead 또는 단독 작업 Agent가 근거를 Issue/PR 또는 작업 계획에 기록하며 별도 사용자 확인은 필요하지 않습니다.
- 고위험은 인증·권한·객체 소유권 검사·계정 격리, 결제·구독 권한·보상·사용량 제한/원장, DB 스키마·migration·삭제/보유 정책·트랜잭션·동시성/멱등성, 공통 HTTP client·직렬화·공유 상태/캐시·보안/실행 설정, 주요 의존성·빌드/실행 환경, 여러 도메인의 결합을 바꾸는 경우입니다. 변경 영향이 불명확하거나 관련 검사 실패·미해결 회귀 우려가 있으면 고위험 기준으로 넓혀 검증합니다.
- 저위험도 담당·직접 연결된 인접 회귀, `git diff --check`, 변경별 타입·빌드·화면·플랫폼·외부 서비스 검증은 생략하지 않습니다. 고위험은 담당 검사 후 영향받는 저장소의 로컬 전체 테스트를 실행하고 DB 등 분야별 필수 검증을 추가합니다.
- 선택 실행의 명령·대상 버전·환경·실제 발견 및 통과/실패 수와 생략한 전체 검사·판정 근거를 기록합니다. 선택 결과가 0개이거나 이전 보고서만 남아 있으면 통과로 처리하지 않습니다. 허용된 전체 생략은 `정책상 생략: 저위험 근거와 대체 검사`로 기록하며 `통과`로 표시하지 않습니다.
- 독립 QA/Review가 필요한 작업에서는 실제 diff와 선택한 검사의 충분성을 검토합니다. 정책상 허용된 로컬 전체 생략은 필수 검사 누락이 아닙니다. 담당·인접·분야별 필수 검사 또는 고위험 로컬 전체의 누락/실패는 기존 완료·병합 금지 기준을 따릅니다.

### 최종 CI와 결과 재사용

- 각 저장소에 현재 정의된 CI의 전체 테스트와 PostgreSQL·빌드·호스팅 등 필수 job은 그대로 유지합니다. 저위험도 최종 커밋의 CI가 통과하기 전 병합하거나 배포하지 않습니다. 로컬 전체 생략을 이유로 workflow·보호 규칙·실패 테스트를 우회하지 않습니다.
- 저위험의 최종 전체 결과는 CI에서 한 번 확인하는 것을 기본으로 합니다. 이 기준은 재실행 금지가 아닙니다. 소스·설정·의존성·환경 변화, 실패 또는 새 위험이 생기면 해당 검사와 CI를 갱신합니다. 같은 대상에서 통과한 검사는 근거 없이 반복하지 않고 QA/Reviewer는 원본 결과를 확인해 재사용합니다.
- 현재 PR·main push의 자동 CI 실행은 유지합니다. 최종 결과를 한 번 확인한다는 기준은 자동 trigger 횟수를 줄이거나 main의 배포 전 CI를 생략한다는 뜻이 아닙니다. CI 이벤트·선별 실행 변경은 별도 작업입니다.
- 미실행/실패는 통과로 표시하지 않고 다음 검증 경로와 영향을 기록합니다. 최종 CI 확인과 운영 배포 승인 기준은 별도 조건으로 유지합니다.

| 변경 범위 | 필요한 로컬 검증 |
| --- | --- |
| 문서·주석만 변경 | diff, 링크·예제·명령과 실제 설정의 일치 확인. 실행에 영향이 없으면 애플리케이션 테스트는 정책상 생략 |
| 저위험 서버 기능/API 변경 | 담당·직접 연결된 인접 회귀. API interface와 `http/*.http` 계약 확인. 로컬 전체 `./gradlew test`는 정책상 생략 |
| 고위험 또는 영향 불명확 서버 변경 | 담당·인접 회귀 후 전체 `./gradlew test`. 변경 분야별 실패·경계·권한 검사 추가 |
| DB·Flyway·삭제·영속적 멱등성·동시성 변경 | 고위험 서버 검사와 실제 PostgreSQL/Flyway 검증. 중복·경합·rollback·데이터 영향 확인 |
| 주요 의존성·빌드·실행/배포 설정 변경 | 관련 검사, 영향 저장소 전체, 실제 build·시작/설정 검증. 운영 확인은 승인된 환경에서 수행 |

담당 Gradle 선택 실행 예: `./gradlew test --tests 'com.example.mypasture.ai.*'`. 분야별 실제 검사가 없거나 외부 서비스가 없어 실행하지 못하면 이유와 영향을 기록하고 필수 검증 완료로 판단하지 않습니다.

## GitHub 작업 흐름

- 기능 구현 전 GitHub Issue를 만들고 최신 기본 브랜치(`main`, 없으면 `master`)에서 작업을 시작합니다.
- Issue 본문은 `.github/ISSUE_TEMPLATE/feature.md` 구조를 따르는 GitHub Flavored Markdown으로 작성합니다.
- 작업 범위, 완료 조건과 검증 계획은 `- [ ]` 체크박스로 작성하고 실제 줄바꿈을 유지합니다.
- GitHub CLI에서는 본문을 Markdown 파일로 준비해 `gh issue create --body-file <file>`을 사용합니다. 백틱이 포함된 본문을 셸의 `--body` 인자로 직접 전달하지 않습니다.
- 브랜치 이름은 `feat/<domain>-<issue-number>-<feature-slug>` 형식을 사용합니다.
- 커밋은 `feat(<domain>): <summary> (#<issue-number>)` 형식을 기본으로 합니다.
- PR의 base는 기본 브랜치(`main`, 없으면 `master`)이며 본문에 `Closes #<issue-number>`를 포함합니다.
- 변경 위험도에 맞는 로컬 필수 검증과 최종 커밋의 전체 CI가 통과한 뒤 승인된 범위에서 merge하고 원격 기능 브랜치를 삭제합니다. 저위험의 정책상 로컬 전체 생략은 허용되지만 필수 CI는 유지합니다.
- 기본 브랜치 병합과 별개로 운영 배포는 사람의 별도 승인이 필요합니다.

## 완료 보고

- 구현한 use case와 정책
- API/DB/환경변수 변경
- 실행한 테스트와 결과
- 모바일/관리자에 필요한 후속 작업
- 미해결 위험
