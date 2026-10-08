# 작업 계약: 169 요일별 말씀과 문맥 본문 자동 순환

## Requirement
일일 관리자 검수 없이 DB 원문과 미리 정의한 안내문을 자동 게시한다. 주제 태그는 선택적이며 토·일에는 주제 제한 없는 문맥 단위 성경 함께 읽기를 제공한다. 전체 성경의 AI 분류와 매일 AI 생성, 운영/스토어 재배포는 제외한다.

## Existing System
서버 base 3cc277043805ffbed1f8a417beee75daffcedfd1, 앱 base 3c8f4f3392e137be4f10a8a89679f054c69ad5b1. 서버 Issue169, 앱136, 관리자56. 기존 daily_words, generation jobs, BibleVerseEntityRepository, DailyWordPublishedPort, 관리자 수동 복구/사건 감지를 재사용한다. 원래 dirty worktree는 보존한다.

## Impact
Large: 서버 영속적 자동 게시·migration·워커와 앱/관리자 표시 연동. 서버 검증 고위험(DB·동시성·공유 실행 설정), 앱은 홈 경계 내부 저위험(담당/인접 회귀와 CI 전체), 관리자 타입/빌드/회귀. 서버 로컬 전체+실제 PostgreSQL 필수. 이 구현의 backend main 병합은 운영 자동 배포를 유발하므로 배포 승인 전 PR 상태로 둔다.

## Work Breakdown
| 작업 | 역할 / 실제 Agent | 허용 경로 |
| --- | --- | --- |
| 계약·카탈로그·서버 자동 제공·통합 | Lead/Implementation: root | server의 daily_word, config/DailyWordProperties, Bible 조회 재사용, notification 본문 범위, 새 migration/설정/http/docs 단일 작성자 |
| 앱 홈·관리자 조회/복구 표시 | Implementation: verse_card | mobile 홈·관련 담당 tests/docs, admin daily-word/types/페이지·관련 tests/docs; 공유 파일 변경은 Lead와 조정 |
| 독립 실패·PG/동시성 QA | QA: stability | 테스트 작성과 검사만, 기능 코드 수정 없음; QA 결과 별도 파일 |
| 독립 설계·호환성 검토 | Reviewer: review | 읽기 전용 diff/검사 원본 확인, Review 결과 별도 파일 |

## Contracts
확정 v1, Lead root. 전달: verse_card/stability/review.
API: GET /api/v1/today-word 기존 fields 유지. endVerse: Int|null(legacy null), topics: string[](HOPE/GRATITUDE/LOVE/PEACE/JOY), readingMode: THEMED|CONTINUOUS|null, passageType: TEACHING|POETRY|NARRATIVE|LAW|GENEALOGY|null 추가. bookCode/chapter/verse는 범위 첫 절, verseText는 범위 원문 전체(여러 절은 절 번호+줄바꿈). verseId는 첫 절. 인증 없이 현재/역사 게시본 조회. 자동 생성은 한국 오늘/내일 범위만, 미게시 과거·먼 미래는 기존404. 잘못된 원문은 게시하지 않음. 관리자 list에도 같은 메타데이터 추가, 기존 명령과 인증 유지.
DB: V42.8 (main/실제42.7 이후, 열린 결제PR43/44 이전). daily_words에 nullable end_verse/reading_mode/passage_type/rotation_entry_id 및 nonnull topic_tags(empty default). singleton daily_word_rotation_lock(id=1) row는 최초배정 다중 인스턴스 직렬화; 날짜 unique는 기존 유지. 옛 migration/게시본 수정 없음. 신규 CURATED 게시본 reviewedBy/reviewedAt null(관리자 검수를 가장하지 않음). 기존 AI DRAFT만 현재/내일 로테이션으로 교체 가능, 수동/반려 초안 보호. 기존 비용 이력은 교체 시 삭제하지 않음. 새 snapshot은 이후 목록 변경/순환에도 유지. 장애 복구는 roll-forward, 코드 롤백 호환성은 기존 fields 유지.
Catalog: 코드의 명시적 범위 목록, 주제당 최소10개, 주제 없는 읽기 최소20개. KOR1910 실제 DB 원문을 읽어 모든 연속 절 존재/비어있지 않음 검증. 주제별 주간 순환(2026-10-05 anchor), 토/일 읽기는 주당2단위의 목록 순서를 유지. topic tags는 비어 있어도 가능. 최초 목록은 versioned CURATED이며 매일 AI 호출 없음.
Schedule: 월HOPE 화GRATITUDE 수LOVE 목PEACE 금JOY 토/일CONTINUOUS. DAILY_WORD_ROTATION_ENABLED true(base/prod), test false. 기존 DAILY_WORD_GENERATION_ENABLED는 worker의 자동 준비 여부이며 APIfalse이어도 현재 날짜 lazy 복구 가능. 워커는 오늘/내일을 준비하고 기존 AI 예약/처리는 rotation enabled 때 정지. 기존 수동 AI 초안 요청은 rotation mode에서 명시적 오류로 처리해 영구 대기 작업을 만들지 않음.
Publication/Event: date unique+DB lock, snapshot/기존 활성 AI job cancellation/기존 알림 outbox 저장은 한 트랜잭션. 기존 게시본·수동 콘텐츠 덮어쓰기 금지. 앞날 알림은 해당날00:00 KST부터 노출하도록 PublishedPort.record 시각을 max(now,date start)로 사용. 같은 날짜 재호출은 단일 게시/알림. API는 서버 검증 원문만 반환.
Interface: automatic service ensurePublished(date) returns stored entity/null; catalog selection returns exact ordered range+tags/mode/type; 오류는 기존 generation/not published handler 재사용.
New dependencies: 없음. 재고갈 fallback: 정해진 목록 순환. 후보 본문 오류는 다음 검증 가능한 항목으로 이동, 전부 오류면 게시 중단+기존 사건 감지. 내용/문맥은 명시적 범위로 보존.

## Dependencies
계약→서버/클라이언트/QA 병렬→서버 담당→서버 전체·PG→통합 독립 Review→각PR 최종CI. 서버 배포 후 앱/관리자 배포, 이번에는 PR까지 준비.

## CHANGE REQUEST: CR-001
Current: verseText에 범위 전체 저장/반환.
Proposed: verseText는 기존 첫 절 원문 유지, nullable passageText에 범위 전체 저장/반환. public/admin 응답에 passageText 추가, 새 클라이언트는 passageText ?? verseText 표시.
Reason: 구버전 서버의 첫 절 검증 및 기존 클라이언트 계약을 보존한다.
Impact: schema/API/클라이언트/PG·원문 검증에 새 필드 추가.
Compatibility: 구버전 서버는 첫 절로 정상 조회 가능, 새 서버/클라이언트는 전체 범위 제공.
Lead Decision: root 수락 v1.1, verse_card/stability/review 전달. 위 v1의 verseText 범위 정의는 이 변경으로 대체.

## CHANGE REQUEST: CR-002
Current: 주제별 주간 순환 및 읽기 순서, 원문 오류 후보는 다음 후보로 이동.
Proposed: THEMED만 최근30일 게시 범위와 겹치지 않는 후보 우선, 모두 최근에 나왔으면 주간 기준 후보로 돌아가 고갈을 막음. CONTINUOUS는 최근중복회피를 적용하지 않아 문맥 읽기 순서 보존.
Reason: 같은 본문이 여러 주제에 속할 때 근접한 요일에 중복 노출되는 것을 줄임.
Impact: 주제 후보 선택의 인접 회귀, 카탈로그 순서/날짜 snapshot은 유지.
Compatibility: 이미 저장된 snapshot은 재선택하지 않음.
Lead Decision: root 수락 v1.2, stability/review 전달. 주제회피만 정책이며 주제 미지정은 제외.

## Risks
주제 분류는 전체 성경 분류가 아니라 소수 명시적 대표 본문이다. 자동 게시가 일일 신학 판정을 하지 않음. scheduler/public request/admin/job의 잠금 순서 및 stale worker가 자동 게시본을 덮어쓰지 못하는지 확인. READ는 오늘/내일에만 자동 저장할 수 있으며 공유 상태 lock과 date unique로 중복 방지.

확정 보완: 모든 새 쓰기 경로의 전역 잠금 뒤 generation_jobs→daily_words 순서를 유지해 구버전 worker와 rolling 경합도 역순 잠금을 만들지 않는다. API/worker는 한국 날짜를 한 번 캡처해 ensurePublished(date,todaySnapshot)에 전달하며 자정 경계에서도 같은 요청의 날짜 기준을 유지한다. KST 이전 미래 날짜 푸시 예약을 차단한다.

## CHANGE REQUEST: CR-003
Current: 클라이언트 feature/tests/docs 경로.
Proposed: verse_card에 mobile/package.json의 scripts.test 새 담당 파일 등록과 admin/scripts/mock-auth-backend.mjs의 GET-only daily-word fixture 단일 작성자 권한 추가.
Reason: 기존 CI에 담당 검사를 포함하고 기존 auth test harness로 화면을 검증한다.
Impact: 테스트 등록/fixture만 변경, 의존성·기존 인증 로직 변경 없음.
Compatibility: 운영 코드·권한 불변.
Lead Decision: root 수락, 담당자에 전달 완료.

## Definition of Done
- [x] 주제별·주제 미지정 문맥 범위·순환·같은날 일관성
- [x] 기존 게시/수동/반려 콘텐츠와 API 호환
- [ ] 서버 담당·전체·실제PG/Flyway 및 클라이언트 타입/빌드/화면
- [ ] 독립 QA·Review와 최종CI
- [ ] PR 및 구현/검증/병합/배포 구분 보고

## 검증 계획
root: Gradle daily_word/notification 회귀→전체, diff check. stability: 실제 PostgreSQL migration42.8/완전한 KOR1910 seed references/동시호출/rollback/알림미래노출/원문불일치/새API 인증경계. verse_card: 앱 typecheck/담당unit/홈PWA phone+desktop/build:web, 관리자 typecheck/build:prod/기존tests 및화면. review: 실제diff/contract/원본 결과 확인. 유료 외부AI 호출·운영 데이터 변경 비해당(정적목록/로컬검사).

## CHANGE REQUEST: CR-004
Current: 앱 홈에서 기기 계산 KST date를 GETtoday-word query에 명시.
Proposed: 홈 기본 GET은 date를 생략해 서버의 한국 날짜 snapshot을 사용. 클라이언트 날짜는 cache key/자정 갱신에 사용, optional history date API는 유지.
Reason: 기기 시계 오차와 자정 네트워크 지연으로 현재 날짜 요청이 과거/미래로 고정되는 것을 피함.
Impact: 홈 담당 PWA/타입 검사 갱신, 서버 계약 불변.
Compatibility: 기존 GET과 동일하며 응답 date가 실제 제공 날짜의 원본.
Lead Decision: root 수락 v1.3, verse_card/review에 전달.

## Implementation Handoff — root
대상: base3cc2770 이후 기능/검사30파일, 원본 diff 식별은 로컬 evidence/server-feature-source.json. 기능 담당 root, 독립 QA stability(새 검사3파일), 독립 Reviewer review. 클라이언트 담당 verse_card는 앱·관리자 별도 worktree에서 진행한다.

자동 제공: explicit84candidate(5×12+24), 한국날짜 singleton DB lock/dateunique/snapshot, jobs→words 일관 잠금과 원자적 cancellation/publication/broadcast. 기존 서버 첫 절 검증 호환을 위해 verseText 보존·passageText 추가. 수동/반려/게시본 보호, 비용·모델 이전 이력 유지. 실제 원문에서 누락된1PE5:6–7 및 PSA16:8–11 후보는 검사 실패를 보존한 뒤2TH3:16/PHP1:3–5로 교체했다. 카탈로그 후보 검증을 완화하지 않았다.

설정: rotation기본true, 기존AIworker순환모드비활성, 전용worker today/tomorrow 준비, public current/tomorrow lazy복구, 미래 알림/KST push가드. 새 deps 없음. migration42.8, public/admin additive API, 기존 수동 복구 API 유지.

### 서버 검사 원본 요약
| 검사 | 명령/환경 | 결과 |
| --- | --- | --- |
| 담당·인접 | ./gradlew test --tests '*DailyWord*Tests' --tests '*NotificationDispatchTests' --tests '*NotificationInboxControllerTests' --no-daemon; test/H2 | 발견47, 통과32, PG조건부건너뜀15, 실패0, exit0/42.60초 |
| 전체 | ./gradlew test --no-daemon; test/H2 | 발견357, 통과327, PG조건부건너뜀30, 실패0, exit0/113.44초 |
| 실제PG | ./gradlew test --tests '*DailyWordRotation*Tests' --rerun-tasks --no-daemon; Docker pgvector16 격리DB/UUID schema | 최종 발견19, 통과19, 건너뜀0, 실패0, exit0/85.90초: service13+migration2+catalog4 |
| 최종catalog | 전체검사의 CatalogSourceTests, 완전 외부copied corpus1188파일 | 4/4 실제실행. corpus없으면CI의원문1건만명시skip하며 나머지logic3건실행 |
| 실행산출물 | ./gradlew bootJar --no-daemon | exit0/10.12초 |
| 운영본문 조회 | 공개Bible GET-only, 84range/57chapter | missing/blank0; 운영 데이터 변경 없음 |
| diff | git diff --check | exit0 |

PG 최초12개의CGLIB 기본인자 Clock-null 실패는 기능 결함이었다. 오늘snapshot의 기본값을 상수null로 변경하고 target method body에서 Clock을 읽도록 수정 후13개service를 실제 재실행했다. API/worker는 단일 캡처date를 명시 전달해 자정 경계도 유지한다. 초기 QA source checker의없는Jsoup참조는 새 deps 없이 표준 parser로 고쳤다. 실패 원본은 로컬 evidence에 별도 보존하고 성공으로 취급하지 않았다.

PGservice13/migration2 결과는 소스·환경이 동일한 최종버전에 재사용한다. PG실행 후 바뀐catalog source검사의corpus-presence gate는 최종전체에서4/4 재실행해 검증했다. 전체로그/XML/종료코드/manifest와 QA/Review 원본은 /private/tmp/mypasture-daily-rotation-20261009/evidence에 보존한다. CI는 새PG조건 env와두class를 포함하며 기존 전체jobs를 유지한다.

Remaining: 클라이언트최종화면/독립통합Review/PR CI 확인 후 결과는 PR에 기록. 구현요청의범위상 운영연결main병합·migration·서버/웹/스토어배포는 미실행한다. 실제배포승인 후 migration→server→app/admin 순서로 진행한다.

### 최종 QA fixture 격리 보완
최초 성공 PG 검사 이후 CI 공유DB의 public schema를 초기화할 가능성을 발견해 service test만 UUID schema/currentSchema/Flyway·Hibernate defaultSchema/qualified TRUNCATE/current_schema assertion으로 격리했다. 실제PG19개를85.90초에 다시 실행해 모두 통과했다. 런타임 소스와 전체 H2에서 실행한327개는 바뀌지 않았고 수정된13개service tests는 전체H2에서 조건부skip이었으므로 전체 결과를 재사용한다. PG 재실행에서 최종 검사 컴파일도 완료했다.

Reviewer LOW: pg_stat_activity의 경합 관측 count는 같은DB의 다른 lock도 포함할 수 있다. 이번 실행은 격리testDB 단독이며 CI job은 순차검사이고, 실제 lock holder와 successor의 완료/단일snapshot·단일broadcast도 별도로 단언하므로 현재 근거는 유효하다. Lead가 이 잔여 관측 한계를 수용했으며 실행 중인 최종fixture를 다시 바꾸거나 같은 전체 검사를 불필요 반복하지 않는다.
