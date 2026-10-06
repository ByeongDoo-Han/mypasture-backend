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

## 테스트 토큰 비용 관리

- 테스트 실행 자체보다 AI가 읽는 로그와 반복 분석을 줄입니다. 비용 절감을 이유로 필수 테스트·CI·독립 QA/Review를 생략하거나 실패를 통과로 처리하지 않습니다.
- 변경 기능의 좁은 검사 → 관련 회귀 → 변경 범위별 필수 전체 검사 순서로 실행합니다. 인증·결제·동시성·데이터 손실 위험의 행동과 실패 조건을 우선하고, 구현을 그대로 따라 하는 테스트나 저위험 수정의 불필요한 테스트 추가는 피합니다.
- 전체 실행 로그는 버전 관리에서 제외된 로컬 파일 또는 CI artifact에 보존합니다. 테스트에서도 토큰·비밀번호·개인정보가 출력되지 않도록 하며, 공유할 근거는 비밀값이 없는지 확인합니다. 대화에는 명령, 대상 버전, 환경, 종료 코드, 통과/실패/건너뜀 수(확인 가능한 경우), 소요 시간과 근거 위치만 전달합니다. 집계할 수 없는 값은 추정하지 않습니다.
- 실패 시 테스트 이름·첫 원인·관련 스택부터 필요한 범위만 읽습니다. 정보가 부족할 때 범위를 넓히며, 같은 전체 로그를 여러 Agent에게 반복 전달하지 않습니다. 로그의 마지막 줄이나 성공 문구만으로 통과를 판정하지 않고 종료 코드와 해당 실행의 보고서를 확인합니다. UP-TO-DATE/FROM-CACHE 및 테스트 미발견은 실제 재실행과 구분합니다.
- 검사별 실행 담당자를 정하고 QA/Reviewer는 같은 대상의 결과 원본을 확인해 공유합니다. 근거 확인과 직접 재실행을 구분하고, 독립 검토자는 실제 diff·관련 코드·실패 조건을 별도로 판단합니다. 근거가 불충분하거나 새 위험을 발견하면 추가 검증합니다.
- 통과 결과는 대상 커밋(미커밋이면 파일 목록과 내용 해시 또는 보존한 diff), 명령·범위, 의존성·설정·외부 서비스 환경이 일치할 때 재사용합니다. 관련 변경·불안정한 테스트·실패·미해결 우려가 있으면 영향받는 검사를 다시 실행합니다. 이전 실행의 남은 보고서를 현재 결과로 사용하지 않습니다.
- Gradle·Docker·브라우저 등 환경 오류는 원인과 전제 조건을 한 번 확인하고, 해결 가능한 설정 변경이 있을 때 재시도합니다. 동일 원인으로 계속 실패하면 준비된 표준 로컬 환경이나 CI로 전환합니다. 테스트를 통과시키려고 매번 별도 classpath·임시 실행기를 구성하지 않으며, 대체 진단 결과는 필수 검증 통과로 간주하지 않습니다. 실행 경로가 없으면 이유·영향·다음 검증 경로를 기록합니다.
- Small은 직접 검사·자체 리뷰, Medium은 구현 담당과 독립 QA/Review 겸임, Large는 별도 QA와 Reviewer를 기본으로 합니다. Agent 인수인계에는 계약·변경 파일·대상 버전·검사 요약·근거 위치·미해결 사항을 전달하고 동일 설명과 성공 로그를 복제하지 않습니다.

## 필수 검증

```bash
./gradlew test
git diff --check
```

변경 범위가 좁으면 담당 테스트를 먼저 실행하되, 인수인계 전 전체 테스트 결과를 남깁니다. 외부 서비스가 없어 실행하지 못한 검사는 이유와 잔여 위험을 기록합니다.

## GitHub 작업 흐름

- 기능 구현 전 GitHub Issue를 만들고 최신 기본 브랜치(`main`, 없으면 `master`)에서 작업을 시작합니다.
- Issue 본문은 `.github/ISSUE_TEMPLATE/feature.md` 구조를 따르는 GitHub Flavored Markdown으로 작성합니다.
- 작업 범위, 완료 조건과 검증 계획은 `- [ ]` 체크박스로 작성하고 실제 줄바꿈을 유지합니다.
- GitHub CLI에서는 본문을 Markdown 파일로 준비해 `gh issue create --body-file <file>`을 사용합니다. 백틱이 포함된 본문을 셸의 `--body` 인자로 직접 전달하지 않습니다.
- 브랜치 이름은 `feat/<domain>-<issue-number>-<feature-slug>` 형식을 사용합니다.
- 커밋은 `feat(<domain>): <summary> (#<issue-number>)` 형식을 기본으로 합니다.
- PR의 base는 기본 브랜치(`main`, 없으면 `master`)이며 본문에 `Closes #<issue-number>`를 포함합니다.
- `./gradlew test`와 CI가 통과한 뒤 merge하고 원격 기능 브랜치를 삭제합니다.
- 기본 브랜치 병합과 별개로 운영 배포는 사람의 별도 승인이 필요합니다.

## 완료 보고

- 구현한 use case와 정책
- API/DB/환경변수 변경
- 실행한 테스트와 결과
- 모바일/관리자에 필요한 후속 작업
- 미해결 위험
