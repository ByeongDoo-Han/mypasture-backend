# 작업 계약: 안정성 보완·말씀카드·독서 업적

## Requirement

사용자가 다음 개발 스텝 중 1,3,4의 진행을 요청했습니다. APP-117 큰 글씨·가로·오프라인 오류 재시도 보완과 실제 사용 검증, APP-133 말씀카드, BACKEND-167/APP-134 독서 업적을 구현·검증·독립 QA/Review·PR/CI까지 진행합니다. QT와 결제는 제외합니다. 새 변경의 운영 배포·스토어 출시는 요청되지 않았습니다. 서버 main 병합은 운영 자동 배포를 유발하므로 준비된 PR에 대해 배포 승인 경계를 마지막에 적용합니다.

## Existing System

- 서버 최신 main: 99732584f21213be8ed9f78beeaa751c7356499a, 앱 최신 main: 09e8ee3b3780a651990b91def98fa1e91a670ab2. 2026-10-08 GitHub와 fetch로 확인했습니다.
- 격리 worktree: /private/tmp/mypasture-growth-20261008/server 및 mobile. 기존 dirty main-2 및 사용자 작업은 보존합니다.
- 준비된 업적/말씀카드 패치는 /Users/byeongdoohan/orca/workspaces/mypasture-server/main-2/docs/tasks/{achievements,verse-card}/에 있으며 과거 검증을 현재 검증으로 사용하지 않습니다.
- 최신 앱에는 세션 generation/refresh 조건부 적용/공통 API 취소가 이미 있으므로 오래된 공통 인증 패치를 덮어쓰지 않습니다.
- 서버 현재 migration 최고 42.6; 열린 결제 PR #135의 미병합 V43/V44가 있습니다. 업적 신규 번호는 42.7 후보이며 최종 CI 및 실제 운영 적용 순서를 다시 확인하고 확정합니다. 기존 migration은 수정하지 않습니다.

## Impact

규모: Large. 별도 기능 UI/PNG/사진과 서버 API/DB/원본 기록/계정 삭제 연동이 있습니다.
검증 위험도: 업적 및 통합은 고위험(권한·DB·트랜잭션·영속적 멱등성). 말씀카드의 native 의존성/공통 설정도 고위험입니다. APP-117 독립 레이아웃은 저위험이나 통합 앱 전체 검증에 포함합니다.

## Work Breakdown

| 기능/역할 | 실제 Agent | 허용 경로 |
| --- | --- | --- |
| Lead·계약·통합 | /root | server migration/HTTP/CI/작업 기록, app navigation/package/config/shared API/auth 및 최종 BibleScreen 공유 연결 |
| 독서 업적 전체 | achievement | server achievement/**, reading/application/ReadingService.kt, bible/application/{BibleBookmarkService,BibleNoteService}.kt, user/infrastructure/JdbcAccountDataErasureAdapter.kt, 해당 신규 테스트/docs/achievements.md; mobile features/achievements/**, profile/{MoreScreen,BookmarksScreen,BibleNotesScreen}.tsx, 업적 단위/PWA/tests/docs |
| 말씀카드 전체 | verse_card | mobile features/verseCard/**, assets/verse-card/**, tests/verse-card* 및 tests/pwa/verse-card*, docs/verse-card.md; 공유 파일 직접 수정 금지 |
| 안정성·기기 조사 | stability | mobile BibleScreen.tsx의 오류 상태/스타일만, tests/pwa/bible-error*, 안정성 검증 기록; 이후 공유 BibleScreen 소유권은 Lead로 이전 |
| QA | stability (업적·카드 구현 종료 후) | 업적/말씀카드의 실제 diff·검사/브라우저/기기 근거, 기능 코드 수정 금지 |
| Reviewer | 독립 review Agent (구현 Agent 종료 후 별도 생성) | 서버/앱 전체 읽기 전용 diff·관련 코드·검사 충분성 검토 |

QA는 본인이 작성한 안정성 수정의 독립 판정을 하지 않습니다. 별도 Reviewer가 해당 diff 및 재현 근거를 확인합니다. Medium/Large의 명목상 역할만 기록하지 않습니다. 모든 결과에 대상 diff/커밋과 로그 원본을 연결합니다.

## Contracts v1 (Lead 확정)

### 업적

API JWT 본인 한정, 기존 API 호환:
- GET /api/v1/achievements: 읽기 전용, 수여 없음.
- POST /api/v1/achievements/reconcile: body 없음, 기존 기록 소급/멱등 수여.
- PUT /api/v1/achievements/featured: {codes:string[]}, 최대3·중복 없음·본인 획득 배지만, 빈 배열 해제. 잘못된 입력400, 미인증401/403, 삭제된 계정은 기존 사용자 검증 규칙.
- 결과 {userId,totalCount,earnedCount,featuredCodes,newlyAwardedCodes,items:[{code,category,title,description,target,progress,unit,awardedAt}]}. progress 0..target, 획득 후 target 고정, 수여 날짜는 최초 실제 수여 시각. GET/featured newlyAwardedCodes=[]; reconcile은 해당 호출의 신규 코드. 고정 catalog 순서/대표 선택 순서.
- 6계열/12개: FIRST_CHAPTER; READ_DAYS_7/30/100/365; READ_STREAK_7; FIRST_BOOK; BOOKMARKS_5/20; NOTE_DAYS_3/10/30. completed=true 및 canonical 장만 판정, 읽기 날짜 distinct, 연속은 역대 최장, 완독은 번역본 중복 제외. 북마크는 현재 서로 다른 위치 보유 수. 메모는 최초 작성 KST 날짜 distinct, 작성일 원장으로 삭제 후 유지. 소급은 남아 있는 기록만. 이미 얻은 배지는 원본 삭제 후 유지.
- DB awards unique(user,code), note_activity_days unique(user,date), featured unique(user,code)/(user,position), position0..2, 소유 awards 복합 FK, 사용자 FK. 계정 삭제는 featured→awards/note-days 삭제. JPA/SQL 일치.
- 원본 행위와 평가·수여는 같은 transaction. 사용자 행 잠금이 원본 변경보다 먼저, JDBC 집계 전 flush, 수여 실패는 원본까지 rollback. DB unique가 영속 멱등성을 보장합니다. reconcile은 복구 경로. Event 없음, 유료/화폐/아이템 보상 없음.
- 앱 query key ['achievements',userId], API 응답 후 userId/generation 재검사, 늦은 조회/대표 선택/계정 전환 캐시 격리. RootStack Achievements undefined, /achievements, 기존 로그인/ACCOUNT gate. 업적 화면 진입 reconcile 및 기록 변경 invalidate. 서버만 수여 판정.

### 말씀카드

- API/DB/Event 변경 없음. 기존 KOR1910 원문 API에서 같은 장의 1~3절 선택, 원문 변조/묵시적 잘림 없음.
- RootStack VerseCard {initialVerse?:BibleVerse}|undefined, /verse-card, 성경 툴바. square1080×1080 / portrait1080×1350.
- 모델 CardDesign: themeId/fontId/fontSize18..34/lineSpacing1.2..1.9/textColor/align/format/photoUri?/photoOverlay0..0.7. 기본360폭 디자인 좌표를 PNG에 확대.
- Preview.exportPng():Promise<string PNG data URI>; 글꼴/사진 로딩 실패 또는 overflow 시 저장 불가.
- pickCardPhoto():Promise<string|null>, saveCardImage():Promise<void>, shareCardImage():Promise<shared|presented|cancelled|downloaded>. 사용자 취소/실패 구분, native OS 종료는 presented로만 안내.
- OFL 글꼴 공식 배포/라이선스 확인. 사진/PNG는 기기 메모리/임시 파일만, 서버·로그·analytics 전송 금지. 기본 앱 렌더링 배경과 직접 사진.
- 웹 우선 실제 Canvas PNG·다운로드·작은/큰 화면 검증. native 구현이 추가되면 SDK54 공식 패키지의 정상 설치/lockfile/설정·build·권한·저장·공유 검증 필요. native 미검증을 웹 viewport로 대신하지 않습니다.

### 안정성

API/DB/Event/권한 변경 없음. APP-117 오류 문구/재시도 버튼은 작은 가로 높이·큰 글씨에서도 스크롤하여 누를 수 있어야 합니다. 기존 성경 목록·본문·해설 유지. 운영 실제 로그인/필사/AI 기록은 접근 가능한 계정·기기 범위에서 확인하며 비밀값을 요청하거나 출력하지 않습니다. Chrome 위험 경고는 캐시 수정과 별개로 현황 확인합니다.

## Dependencies / Configuration

기능 계약 확정 → 신규 기능 병렬 구현 → Lead 공유 경로/의존성/migration 통합 → 담당 검사 → 고위험 전체 및 PostgreSQL → 독립 QA/Review → 수정/재검증 → 최종 CI. 서버 API/DB가 운영 앱의 업적 노출보다 선행해야 합니다. 배포 workflow/보호 규칙은 우회하지 않습니다.

## Risks

오래된 패치와 최신 auth/reading/계정 삭제의 충돌, SQL 집계/N+1, 잠금 순서와 rollback, 날짜/번역본 중복, 사진 권한/임시 파일/글꼴 네트워크·라이선스, 실제 기기 또는 로그인 계정 접근 제한. 과거 mock/직접 classpath 검사는 현 표준 검사 통과가 아닙니다.

## 검증 계획

Lead: git diff --check, 담당·인접 Gradle → 전체 ./gradlew test, 실제 Docker PostgreSQL/Flyway 및 업적/기존 PG 회귀; 앱 typecheck/담당단위 → 전체 npm test/build/PWA/hosting 및 실제 PNG/화면. native 설정/빌드는 가능한 표준 경로에서 수행. 테스트 로그는 /private/tmp/mypasture-growth-20261008/에 보존하고 개수/종료코드/실제 재실행을 기록합니다. QA/Reviewer는 동일 원본 결과 재사용 및 필요한 독립 실패 검사로 판단합니다. CI 서버의 기존 모든 job/앱 전체 job 유지.

## Definition of Done

- [ ] 요구된 사용자 흐름과 실패/취소/계정 격리 동작
- [ ] API/DB/Interface/문서 일치
- [ ] 필수 로컬 검사·PostgreSQL·실제 PNG/화면 및 플랫폼 검증
- [ ] 독립 QA/Review 완료 및 수정 후 재검증
- [ ] 최종 PR 커밋 CI 확인
- [ ] 구현/검증/병합/배포 상태·남은 제약 명확히 보고

## Change Requests / Lead 결정

### CR-001: 계정 전환 중 기록 요청의 소유자 고정 (수락)

Reviewer가 query 취소 await 중 계정이 바뀌면 북마크/메모가 새 계정으로 전송되는 경합을 재현 가능한 원인으로 확인했습니다. Lead가 Bible API의 선택적 소유자(userId+sessionGeneration) 인자와 요청 전 검증, await 뒤 optimistic cache/rollback/settled의 소유자 검증을 통합했습니다. 이전 호출자 호환을 유지하며 실제 API의 다른 계정/동일 계정 재로그인 전환 6개 회귀 검사에서 네트워크 미전송을 검증했습니다. 허용 경로는 Bible api.ts, BibleScreen, 해당 기존 격리/검색 harness 검사와 profile BookmarksScreen의 mutation wrapper까지 확장했습니다.

### CR-002: 실제 가로 오류 화면과 테스트 선택자 (수락)

단순 내부 스크롤만으로는 고정된 상단 영역이 남기는 높이가 버튼보다 작을 수 있었습니다. APP-117 담당자가 loading/error/unpublished 경우에만 상단 탐색까지 함께 스크롤하는 BibleBodyFrame을 추가했습니다. 정상 본문은 기존 고정 메타데이터와 내부 스크롤을 유지합니다. 버튼 bounds/hit-test/recovery 조건은 완화하지 않고 280/360px 높이를 검증합니다. Ionicon이 포함된 실제 접근성 이름에 맞게 테스트 선택자만 보완했고 실패 결과는 별도로 보존했습니다.

### CR-003: 이전 서버 버전의 탈퇴 호환성 (수락, 계약 v1.1)

기존 배포 절차는 migration을 선적용하고 실패 시 이전 API 이미지로 롤백합니다. 이전 AuthService도 users를 소프트 삭제하므로 FK의 물리 삭제 cascade만으로 새 업적 테이블을 지우지 못합니다. Reviewer가 HIGH R5로 확인했습니다. 아직 미출시인 신규 V42.7에 active→deleted 사용자 전환 AFTER UPDATE 트리거를 추가해 featured→awards→note-days를 같은 트랜잭션에서 지웁니다. 현재 adapter의 명시 삭제는 idempotent하게 유지합니다. API/사용자 정책/이전 클라이언트 계약은 동일합니다. 실제 PG에서 이전 방식 직접 SQL 삭제·rollback·무관한 프로필 변경을 검증합니다. 이미 적용된 운영 migration은 수정하지 않습니다. 기존 테스트 DB는 보존하고 별도 growth_guard_test DB에서 신규 미출시 migration checksum을 검증합니다.

## 서버 구현 인수인계와 최종 로컬 검증

- 도메인 Owner: Scripture Intelligence가 신규 achievement와 reading/bible 연동을 담당합니다. Backend Foundation의 계정 삭제 연동은 Lead가 계약과 공유 파일 소유권을 조정했습니다.
- 실제 구현: /root/achievement가 서버 업적·원본 기록 hook·앱 업적을 구현했고, /root가 V42.7/HTTP/CI와 공유 앱 계약을 통합했습니다. /root/stability가 업적/카드 독립 QA를 수행하고 /root/review가 별도 실제 diff·실패 조건·원본 검사 결과를 검토했습니다.
- 최종 서버 전체 `./gradlew test --no-daemon`: exit0, 249.557초, 338 tests /323 pass /15 PostgreSQL 전용 skip /0 failure /0 error. 표준 Gradle/Java17 toolchain. 원본 server-full-final-xml 및 server-full.log. 실제 실행이며 test task UP-TO-DATE가 아닙니다.
- 최종 실제 Docker PostgreSQL16.15: 선택한 업적·삭제 가드·인증·검색·활동·AI 기록/문맥·필사 회귀 `./gradlew test --tests ... --rerun-tasks --no-daemon`: exit0, 241.108초, 101 tests /101 pass /0 skip /0 failure /0 error. ACHIEVEMENT/ADMIN_EMAIL_LOGIN/BIBLE_SEARCH/ACTIVITY/AI_HISTORY/SCRIPTURE_COPY_TEST_JDBC_*를 task 전용 growth_guard_test DB에 지정했습니다. 기존 테스트 DB는 보존했습니다. 원본 server-pg-erasure-guard-xml 및 server-pg.log.
- 최초 PostgreSQL 98개 중 1개 실패는 TEST_TRANSLATION 절 fixture의 장 FK 준비 누락이었습니다. 실제 장을 생성하도록 수정했으며 DB 제약은 완화하지 않았습니다. 수정 후 98개 모두 통과했고, 삭제 보호 계약 추가 후 새 DB의 101개 최종 결과로 갱신했습니다.
- 신규 삭제 가드 3개는 구버전 직접 SQL 경로 삭제/타인 보존, rollback 원상 복구, 프로필 변경·중복/무관한 전환을 실제 PG에서 검증합니다. H2 전체의 PG 전용 건너뜀은 실제 PG 실행으로 보완했습니다. MockMvcPrint.NONE으로 새 API 검사 실패 시 토큰 출력도 막습니다.
- Reviewer 최종 서버 판정: MERGE READY, 미해결 BLOCKER/HIGH/MEDIUM 없음. 운영 migration 최신42.6과 미병합 결제43/44를 확인했으며 아직 출시하지 않은42.7만 작성했습니다. `git diff --check` 확인.
- 근거 위치: /private/tmp/mypasture-growth-20261008/의 검사별 log/result.json/XML/QA/Review/인수인계/source hash. 원본 로그는 저장소에 커밋하지 않습니다.
- 구현·로컬 검증은 완료이며 최종 커밋 CI는 PR에서 확인합니다. main 병합은 운영 자동 배포를 유발하므로 새로운 업적 운영 배포 승인 없이 진행하지 않습니다. 앱의 최종 PNG/전체 PWA/Android·iOS 검증은 진행 중이며 서버 API/DB 선행 운영 반영 후 앱 배포가 필요합니다.


### CR-004: PostgreSQL CI 반복 컴파일 제거 (수락, 검증 범위 유지)

- Current: postgres-auth job에서 인증/검색, 활동, AI, 필사, 업적을 다섯 Gradle 프로세스로 나누고 후속 네 번에 --rerun-tasks를 사용합니다. Java/Kotlin 및 테스트 소스를 반복 컴파일합니다. GitHub job113162232297은 앞 네 단계가 성공한 뒤 업적 단계에서 취소됐고, check-run annotation은 `The job has exceeded the maximum execution time of 15m0s`를 명시합니다. 취소된 업적 CI를 통과로 간주하지 않습니다.
- Proposed: 동일 PostgreSQL 서비스와 여섯 JDBC 환경 변수 계열(ADMIN_EMAIL_LOGIN, BIBLE_SEARCH, ACTIVITY, AI_HISTORY, SCRIPTURE_COPY, ACHIEVEMENT; 총18개)을 한 단계로 합치고, 모든 기존 선택 클래스13개를 한 `./gradlew test ... --rerun-tasks --no-daemon` 명령에서 실행합니다. 강제 재실행도 한 번 유지해 실제 PostgreSQL 검사가 캐시 결과로 대체되지 않게 합니다.
- Reason: 각 단계의 필수 테스트를 유지하면서 동일 소스의 반복 컴파일·Gradle 프로세스 시작 시간을 줄여 현재15분 job 예산 안에 검증을 마칩니다.
- Impact: .github/workflows/ci.yml의 postgres-auth 단계 통합만 실행 동작이 바뀝니다. 사용자 기능/API/DB와 다른 CI job, trigger, service, timeout, 보호 규칙, 검증 조건은 동일합니다. 서로 다른 클래스의 Spring context와 JDBC 환경이 동시에 존재하는 위험은 같은 여섯 계열을 한 표준 Gradle 명령에 지정한 실제 로컬 PostgreSQL101개 통과 결과로 확인했습니다.
- Compatibility: AdminEmailLoginControllerTests, BibleCursorSearchControllerTests, ActivityCalendarControllerTests, ReadingActivityIntegrationTests, AiAnswerHistoryTests, AiHistoryControllerTests, AiContextSnapshotTests, AiContextDeletionGuardPostgresTests, ScriptureCopyControllerTests, ScriptureCopyLegacyInsertPostgresTests, AchievementControllerTests, AchievementCatalogTests, AchievementErasureGuardPostgresTests 전체를 유지합니다. 로컬 통과 명령의 `*Achievement*Tests`를 CI의 업적 클래스 세 개로 전개했을 때 선택 집합이 일치합니다.
- 담당/검증: Lead가 변경 범위와 수정안을 승인하고 /root/achievement에 해당 CI·작업기록 경로를 배정했습니다. Agent는 YAML 구조, 이전/이후13개 클래스와18개 env 값의 보존, 실제 로컬 exit0·101개 결과와의 명령 일치를 검증합니다. 독립 Reviewer가 실제 diff를 검토하고 Root가 최종 커밋을 push하여 전체 CI를 재확인합니다. 로컬 애플리케이션 소스/테스트/설정은 바뀌지 않아 동일 대상의 기존 전체323개 및 PostgreSQL101개 결과를 재사용하며 새 CI 실행을 생략하지 않습니다.
- 근거: /private/tmp/mypasture-growth-20261008/server-ci-pg-cancelled.log, server-pg-ci-consolidation-verification.json, server-pg.result.json 및 server-pg-erasure-guard-xml. 취소 원본은 보존합니다. CI 최종 결과는 재실행 후 별도 갱신합니다.
