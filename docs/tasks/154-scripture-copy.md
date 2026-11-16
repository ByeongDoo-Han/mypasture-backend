# 작업 계약: MP-154 / APP-121 성경 필사

## Requirement
사용자가 승인한 회색 원문 위의 따라 쓰기: 원문 지속, 작성 부분 검정, 오타 밑줄, 한글 조합, 지우기, 명시적 완료. 성경 선택 한 절 시작, 자동 서버 저장/이어 쓰기, 선택적 묵상, 내 필사 목록/상세/삭제. 기억하며 쓰기·사진·손글씨·읽기완독 자동 처리·보상·배포/스토어 출시 제외.

## Existing System
server origin/main fbad2e1, app origin/main 6fb9b6f에서 별도 worktree 생성. 기존 dirty checkout 보존. 서버 BibleReaderService/UserRepository 행 잠금/계정 파기/Api interface, 앱 apiFetch expectedUserId/Query/PolicyConsentGate/Bible 단일 절/More 재사용. 신규 라이브러리 없음.
Issues: server https://github.com/ByeongDoo-Han/mypasture-backend/issues/154 / app https://github.com/ByeongDoo-Han/mypasture-app/issues/121 . 필사 관련 기존 Issue 검색 결과 없음.

## Impact / Work Breakdown
규모 Large: 앱·서버·인증된 영속 기록·DB/탈퇴·IME 및 플랫폼 연동.
- Lead / Frontend implementation / 공유 작성자: root. app features/scriptureCopy/**, BibleScreen, MoreScreen, navigation, package test 선택자. server migration/http/CI/docs tasks 및 앱 문서.
- Backend implementation: scripture_backend. server bible/{domain,application,infrastructure,presentation}/*ScriptureCopy*, 해당 서버 테스트, user/infrastructure/JdbcAccountDataErasureAdapter의 새 기록 파기 한 줄. migration/설정/CI 수정 금지.
- QA: scripture_qa. tests/pwa/scripture-copy.spec.ts만 테스트 작성 허용; 제품 코드 수정 금지. 실패·IME·계정·desktop/mobile·검사 근거 검증.
- Reviewer: scripture_review. 제품 코드 수정 금지; 실제 diff/서버·앱·동시성/보안·호환성 검토. QA와 독립.
도메인 Owner Scripture Intelligence(bible 필사), Backend Foundation(탈퇴); Lead 조정. 공유 경로 단일 작성자는 root.

## Contracts v1 — 확정 / Lead root
API 로그인 본인 전용, JWT userId, /api/v1/scripture-copies:
- PUT /{id}: client UUID, {version,bookCode,chapter,verse,typedText,reflection,expectedRevision,complete}. 신규 expectedRevision=0, 최초 revision=1. version/bookCode는 trim uppercase, 위치 양수, typedText 최대10000 UTF-16 문자, reflection 최대1000 문자. 서버가 성경 원문·권 이름·canonical 위치를 조회해 snapshot을 보존, 클라이언트 원문 불신. 기존 위치 불변.
- 응답 {id,version,bookCode,bookName,chapter,verse,sourceText,typedText,reflection,revision,status:DRAFT|COMPLETED,createdAt,updatedAt,completedAt:null|ISO}. 서버 완료 판정은 NFC 정규화 후 Unicode Letter/Mark/Number만 비교(공백/문장부호/줄바꿈 무시); canonical 원문 비어 있으면 완료 불가. 모든 실질 글자가 일치해야 완료. 완성 전 입력 저장 허용. completed 기록 수정 불가. 완전히 동일한 기존 content/status/location의 PUT 재시도는 같은 revision 반환; 그 외 expectedRevision 불일치409, 수정 성공 revision+1.
- GET ?page=0&size=20: {items:응답[],page,size,totalElements,totalPages}, page>=0,size1..50, updatedAt DESC,id DESC.
- GET /{id}: 본인 기록 상세.
- DELETE /{id}?expectedRevision=N: 본인 기록 revision 확인 후 삭제204, 멱등 재삭제204. 소유하지 않은 기존ID는404.
- 오류: 무인증401, 검증400 INVALID_REQUEST, 없는 본문404 BIBLE_NOT_FOUND, 타인/없는기록404 SCRIPTURE_COPY_NOT_FOUND, 충돌409 SCRIPTURE_COPY_CONFLICT, 미완성 완료400 SCRIPTURE_COPY_INCOMPLETE. Entity 직접응답 금지.
DB scripture_copies(id UUID PK,user_id UUID FK users ON DELETE CASCADE, version/book_code/book_name, chapter/verse positive, source_text/typed_text/reflection TEXT, revision BIGINT>=1,status varchar(16) check,created_at/updated_at/completed_at TIMESTAMPTZ). index(user_id,updated_at DESC,id DESC). 모든 쓰기는 active user 행 잠금 뒤 record조회/쓰기; 계정삭제와 같은 user lock 순서. source location/snapshot immutable. 개인 기록 계정 탈퇴 시 명시 파기.
Migration V42.3 확정(CR-002): 현행main V42.2 뒤이며 열린 billingPR135 V43/V44 앞. 미반영 achievements V45와도 번호가 다름. 실제 운영 적용상태 조회는 기록 미발견으로 미확인, 최종 병합 전 실제 적용순서 재확인. 기존 migration 수정/out-of-order 금지.
Event 없음. Configuration 신규 env 없음, 서버 배포 선행. 이번 main 병합은 자동 운영 배포 연결 때문에 범위 밖; reviewable PR과 CI까지 준비.
Frontend 입력 원문 영구 표시, 실제 typed overlay+회색 source suffix, 조합 중 오류 판정 유예. 일반 입력기 사용, 붙여넣기 허용(부정방지 없음). 원문 숨기기/암송 없음. 완료는 버튼으로 명시. 저장 상태/실패/재시도/409 reload(현재 입력 보존·자동 덮어쓰기 금지)/계정 변경 초기화. 웹 localStorage/sessionStorage/service worker에 개인 기록 저장 금지; Query는 userId별, 동일사용자 재로그인까지 session 토큰 경계로 이전 요청 폐기. in-memory dirty 자동저장은 순차 처리하고 서버 응답이 최신 입력을 덮지 않음. 화면이탈 dirty는 확인/최종 저장 지원.
Navigation RootStack ScriptureCopy:{verse?:BibleVerse,id?:string}|undefined, ScriptureCopies:undefined; deep link scripture-copy/:id?, scripture-copies. 보호된 ACCOUNT gate. Bible 선택 toolbar와 More 목록 진입. 한 절 진입 위치는 API서버가 확인. Source 렌더링 폰트·행간·padding은 TextInput과 동일.

## Dependencies / Risks
계약→backend와frontend 독립 구현→담당검사→통합→QA/Review→수정재검증→최종커밋CI. 개인 데이터/계정전환/완료검증·동시성·한글조합·겹침·네트워크실패 우선. 네이티브 actual 기기 검증 없으면 미실행으로 기록. Docker daemon 꺼짐: 실행환경 준비 후 실제PG 검증, 불가능하면 완료로 표현하지 않음.

## Definition of Done
- [x] 원문지속/입력/삭제/IME/완료와 기록 흐름
- [x] API/OpenAPI/http/DB/탈퇴/앱계약 일치
- [x] 서버 담당/전체, 실제PG/Flyway·중복·충돌
- [x] 앱 담당/전체/typecheck/build:web/test:pwa desktop+mobile
- [x] 독립 QA와 별도 Review, 미해결 BLOCKER/HIGH 없음
- [ ] 최종 커밋 CI/PR 준비, 운영 자동 배포에 따른 미병합 상태 명시

## 검증 계획
Backend 담당: ./gradlew test 담당→전체, 정식 postgres용 task/환경 재사용. Lead Docker/PG 환경 및 Flyway 제공. Frontend Lead: 담당단위→npm test/typecheck→build:web 순차, QA test:pwa 및 screenshots. 로그는 /private/tmp/scripture-copy-checks/에 보존, 종료코드·XML/JSON 집계. Reviewer 원본 근거/실제diff 별도 검토. 모든변경 git diff --check.

## Change Requests
### CR-001 — 명시적 인증 세션 경계 / 수락, Lead root
Current: authStore/apiFetch는 userId만 비교해 동일사용자의 새 로그인과 정상 refresh를 구분하지 못하며 저장소 await 뒤 늦은 상태 적용이 새 로그인/로그아웃을 덮을 수 있음.
Proposed: sessionGeneration은 로그인/로그아웃/복원 경계에서 증가. refreshSession은 generation/userId/refreshToken 확인 후 동일 세션 토큰만 교체. 메모리 먼저 반영하고 저장소 쓰기를 직렬화. 공통 client도 generation 검사/refresh timeout15초 적용.
Reason: 필사 응답·재시도·저장 상태의 세션격리와 refresh 네트워크 장애 복구.
Impact: app shared/state/authStore.ts, shared/api/client.ts 및 기존 client mock/test. root 공유작성, QA/Reviewer 독립검토.
Compatibility: 외부 API/저장소 형식 변경 없음. 모든 앱 화면 회귀검사. 정상 refresh는 generation을 유지함.
### CR-002 — Migration 순서 / 수락, Lead root
Current: V45 제안은 아직 병합되지 않은 결제 V43/V44보다 먼저 배포될 때 결제 migration이 누락될 위험.
Proposed: V42.3__scripture_copies.sql로 번호 확정, 새 일회용 DB에서 clean/Flyway 검사. 기존 applied 파일은 무변경.
Reason: 필사 기능을 미병합 결제 작업에 의존시키지 않고 순서 보존.
Impact: root migration/문서, backend finalPG/전체 재검증. 실제운영 적용상태는 배포 전 재확인.
Compatibility: 스키마/API 변경없음. backend/QA/Reviewer에 전달.

## 복구 동작 명확화
409 즉시 현재 입력을 보존하고 자동 저장을 멈춤. 최신 기록 불러오기는 별도 확인에서 사용자가 불러오기를 명시한 경우에만 현재 입력을 교체하며 취소는 그대로 유지. 오프라인/원격삭제 등 저장 불가 시 저장하지 않고 나가기 제공, 진행중 저장 settle 뒤 writer.stop하고 제거. 네트워크 무응답은 요청/refresh timeout으로 종료.

## 구현 인수인계 / Lead root
대상은 server fbad2e1 및 app6fb9b6f의 미커밋 변경. 변경된 제품 소스는 2026-10-06 최종검사 후 동결. 실제 해시는 /private/tmp/scripture-copy-checks/backend-target-final-v423-sha256.json 및 app-tested-source-manifest.json에 보존하고 최종커밋과 내용 일치 확인.
Backend scripture_backend는 bible의 필사service/entity/repository/Api/controller/exception 및 HTTP통합test, 계정파기 한 줄을 작성. Root는 frontend 모델·queue·sessionguard·편집/목록·navigation/Bible/More·인증세션CR·단위test와 서버migration/HTTP/CI/docs 단일작성. QA scripture_qa는 제품파일 수정 없이 Playwright 기능test를 작성/실행. Reviewer scripture_review는 실제제품diff/원본근거를 읽기전용으로 검토.
API/DB 변경은 Contracts v1과 V42.3. Event/new env/dependencies 없음. 서버선행 준비 필요. 운영 배포 요청 없음.

### 검사 원본
- 담당 H2: Java17 ./gradlew test --tests '*ScriptureCopyControllerTests', exit0,9개 중8pass/1PGskip,20초.
- 실제PG16: SCRIPTURE_COPY_TEST_JDBC_* 지정 ./gradlew clean test --tests '*ScriptureCopyControllerTests', exit0,9pass/0skip,47초. fresh DB45migrations→42.3 및 Hibernate validate. duplicate/update/계정탈퇴 경합 포함.
- 서버전체: ./gradlew test, exit0,53suite260tests257pass/3PG-onlyskip/0fail,122초. 신규 필사PG-only는 위 실제PG로통과. 전체 XML과 logs backend-final-summary.json 참조.
- app 담당 단위16개 및 전체 npm test113pass/0fail/0skip,exit0. modelUnicode/IME오류유예/직렬autosave/실패retry/늦은응답/동일계정재로그인/refresh/저장소경합/timeout 검증.
- npm run typecheck 및 build:web: exit0. API는 공개 모의주소 https://api.example.test, 운영용산출물로배포하지않음.
- 브라우저 QA/최종Review 결과는 아래에 추가. initialnarrow 12pass/4fail은 hidden-screen selector2개와 실제삭제목록stale2개; 수정후targeted6pass는 이전산출물 검사이며 최종full로대체 예정. 초기full은 root의후속2줄 selector보완 빌드교체와겹쳐SIGINT130종료, 통과근거로불사용.
- 초기 서버 blank원문404 fixture 컴파일경합실패는 원본보존, 최종으로대체. PG Instant정밀도는 MICROS정규화 후재검증. migration번호변경 후 clean/freshDB로검사.
- actual native IME/keyboard/OS실기기·운영API검증은 미실행. 네이티브모듈/설정변경은없고 desktop/mobile Chromium모의API 검사를 실기기나운영검증으로표현하지않음.
- 현재workspace 원본dirty 사용자변경은수정하지않음. 계정데이터·비밀값로그없음. 로그·screenshots/XML은 /private/tmp/scripture-copy-checks에보존, repo에는요약만.

## 독립 QA / Review 최종 결과
- QA scripture_qa: [QA 원문](154-scripture-copy-qa.md). 제품수정없음, 필사Playwright tests 단일작성. npm test:pwa exit0,desktop/mobile82pass0fail0skip0flaky,146.301초; 신규필사16개 포함. 원본JSON/XML와실제source/dist60개hash일치확인. 합성composition/실제browser clipboard이며실제OSIME아님.
- Reviewer scripture_review: [Review 원문](154-scripture-copy-review.md). 제품수정없음, QA와별도 실제diff/관련코드/원본증거검토. 발견한HIGH/MEDIUM/LOW 모두수정·재검증확인, 미해결BLOCKER/HIGH없음. 판정MERGE READY(최종CI/배포승인별도).
- app 최종검사 개별종료코드도 app-checks-verified.json에 직접보존: typecheck0/3.369초,unit0/2.116초(113pass),hosting0/1.925초. 최종build exit0,PWA4c6e17a37e7303a288b1. 동결된 제품source와 QA 최종source/dist 일치.

## Lead Integration
Root가 실제 server/app diff, HTTP/API/schema, 계정파기·canonical완료·sessionguard/autosave·복구계약과 원본검사/독립QA·Review를 확인. 휴대폰/desktop PNG 두크기 직접시각확인, 검정/회색입력정렬과 가로overflow없음. 검토 이후 변경은 이 기록과 원문보고서/앱요약 문서 추가뿐이며 제품변경없음. git diff --check와 신규파일내용공백/문서링크 검사 완료.
최신 origin/main serverfbad2e1/app6fb9b6f 재fetch 일치. 최종 commit/PR/CI 진행상태는 연결된 PR에기록해 문서완료기록 때문에 새제품검사/CI를 무한반복하지않음. 서버main병합은 운영자동배포이고이번구현요청에운영배포승인없음, app공개는서버준비후이므로 두PR 준비상태로유지. 실제운영migration상태/실기기IME/운영핵심흐름 검증은 배포/출시단계에남음.
구현완료, 로컬웹/서버검증·독립QA/Review완료, 최종CI는PR에서추적. 병합·운영배포·스토어출시없음. 기존사용자작업트리보존.
