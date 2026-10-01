# 작업 계약: 성경 이름·구절 cursor 검색 (server #149 / app #110)

## Requirement
검색어 한 글자부터 성경 권 이름의 포함 결과를 위에, 본문 포함 결과를 아래에 표시합니다. 구절은 20개씩 더보기로 추가하며 결과 선택 시 성경 본문으로 이동합니다. 비로그인 허용. 기존 검색 API 호환 유지. 배포/스토어 출시 및 이전 미반영 기능 패치는 제외.

## Existing System
server origin/main ed4fc1a, mobile origin/main 07f4750. 서버 BibleApi/ReaderService/JpaBibleContentRepository에 page 검색, DB bible_books/bible_verses 및 lower(text) trigram 인덱스가 있습니다. 앱 BibleScreen은 현재 장 필터, TanStack Query/FlashList, 공개 Bible 탭·본문 위치 이동을 사용합니다. 기존 dirty 작업을 보존하여 server search-149, mobile search-110 worktree를 최신 원격 main에서 생성했습니다.

## Impact
Large: 서버 검색 계약·DB 쿼리·앱 화면/라우팅을 함께 변경하므로 병렬 구현 및 별도 QA/Reviewer를 적용합니다. 스키마·인프라는 변경하지 않습니다.

## Work Breakdown
- Lead root: 계약·공유 HTTP/CI/문서/앱 package 및 최종 통합. 서버 src는 backend 단일 작성자, 앱 src는 frontend 단일 작성자(이번 작업에 한해 navigation도 위임).
- Backend activity_backend: server src/main 및 src/test의 bible 검색 slice만. DB migration/CI/http/build 설정 변경은 Lead에게 CR.
- Frontend process_review: mobile src/features/bible/**, src/navigation/AppNavigator.tsx 및 기능 단위 테스트. shared/auth 변경 금지.
- QA workflow_review: 독립 실패/경합/실제 웹·모바일 viewport 검증, mobile tests/pwa/bible-search.spec.ts 단일 작성자. 제품 코드 수정 금지.
- Reviewer: backend 구현 종료 후 activity_backend가 앱을, frontend 종료 후 process_review가 서버를 독립 리뷰합니다. QA와 별개. Lead가 보고·실제 diff를 대조합니다.

## Contracts
확정 v1, Lead root. Backend/Frontend/QA에게 문서 전달.
API: 기존 GET /api/v1/bibles/{version}/search?keyword&page&size 유지.
신규 GET /api/v1/bibles/{version}/search/cursor?keyword=<text>&size=20&cursor=<opaque optional>.
- 공개 GET, 사용자 상태 변경 없음, 동일 요청 멱등.
- version 기존 지원 버전 규칙/대소문자 정규화. keyword NFC+trim, 1..50자. 대소문자 무시한 문자 그대로 포함 검색(%, _, escape 문자도 wildcard 아님). 공백 입력은 앱에서 요청 안 함; 서버400. size 기본20 범위1..50.
- 응답 {version,keyword,size,books: BibleBookResponse[],results: BibleVerseResponse[],hasNext:boolean,nextCursor:string|null}. books는 전체 이름 부분일치 결과 정경 순, 매 페이지 동일. 구절은 권 displayOrder/장/절/ID 오름차순 keyset, size+1 조회로 hasNext 결정. OFFSET/COUNT 없이 조회. 마지막/빈 결과는 false/null.
- cursor는 버전1 opaque base64url, version+정규화 검색어 fingerprint+마지막 위치를 바인딩. 길이/구조/위치 유효성 확인, 다른 검색어/번역본·손상 cursor는400 INVALID_BIBLE_REQUEST. 지원하지 않는 번역본404 BIBLE_NOT_FOUND. 내부 예외/원문 cursor 노출 금지. size 변경은 허용. 공개 정적 본문 탐색용이며 서명/새 secret 불필요. 적재 중 원문 변경의 snapshot 보장은 없음.
DB: 없음. 기존 인덱스·제약 재사용. join fetch 또는 projection으로 N+1 방지. PostgreSQL 실검색·한글 한 글자/일반어 실행계획 확인; 단문은 trigram 이득이 작을 수 있어 전체 count 제거와 bounded page로 제한.
Event: 없음.
Interface: 앱 searchBible(version,keyword,cursor?,size=20,signal?)가 응답 타입 반환. TanStack useInfiniteQuery key는 version/정규화검색어, nextCursor 그대로 전달, 수동 더보기만. 입력 debounce300ms, 이전 검색 결과 혼합 방지, 중복 더보기 방지. 목록은 가상화, 검색어 초기화/변경 시 첫 페이지로 복귀. 첫 로딩/빈 이름·구절/초기 오류 재시도/더보기 오류(기존 결과 유지) 제공.
Configuration: 새 라이브러리·환경변수 없음. 서버API 선행 배포 후 앱. 배포 승인 이번 범위 없음.

## Dependencies
계약 → 서버/앱 병렬 → 실제API 계약 통합 → 담당 검사/QA/교차 Review → 수정 → 최종 CI/PR. QA는 테스트 설계 먼저, 제품 구현 완료 뒤 화면 검증.

## Risks
한 글자 검색의 DB 비용, cursor 유효성/문자열 wildcard, 검색어 빠른 변경의 늦은 응답, 더보기 중복 요청, 잘못된 본문 이동, 이전 offset 클라이언트 호환. 이미 준비된 표준 도구만 사용하고 성공 로그는 요약 공유.

## Definition of Done
- [x] 한 글자/성경 권 이름/구절 포함/더보기/본문 이동 구현
- [x] API interface/HTTP/앱 타입 계약 일치
- [x] 담당 및 전체 서버 검사, PostgreSQL 실제 쿼리 검증
- [x] 앱 typecheck/test/build 및 모바일·데스크톱 화면 검증
- [ ] 독립 QA/교차 Review, 최종 커밋 CI/PR
- [ ] 구현/검증/병합/배포 상태 및 남은 이슈 보고

## 검증 계획
Backend: 단위 cursor/입력 및 API 통합(요,특수문자,모든 페이지,경계·변조/다른검색어/빈검색어,기존 page). Lead가 전체 Gradle과 실제 PostgreSQL 담당. Frontend: 모델/요청 단위 및 typecheck, Lead 전체 test/build. QA: Playwright 실제 Chromium desktop/mobile에서 검색/더보기/지연응답/오류재시도/clear/본문 이동, 원본 근거 확인. 코드 변경 없는 검사는 대상 내용 동일하면 재사용. 원본 로그는 /private/tmp/bible-search-*에 저장, 요약/실패 부분만 공유.

## CHANGE REQUEST CR-001 / 수락 (Lead root)
Current: 공통 apiFetch는 init.signal을 내부 timeout signal로 덮어써 검색 취소가 네트워크에 전달되지 않습니다.
Proposed: 외부 signal을 내부 AbortController에 연결하고 기존 15초 timeout과 함께 JSON/오류 본문 처리까지 유지, finally에서 timer/listener 해제. 취소된 요청은 재인증 분기/재시도/결과 반영 전에 AbortError로 종료합니다.
Reason: 빠른 검색어 변경 및 화면 이탈의 불필요한 네트워크/서버 작업을 줄이고 확정 signal 인터페이스를 실제 이행합니다.
Impact: root 단일 작성 shared/api/client.ts 및 tests/api-cancellation.test.cjs, 앱 전체 회귀·QA. 인증 저장소/refresh 구조는 변경하지 않습니다.
Compatibility: 함수 인자/응답/API 변경 없음, signal을 제공한 호출자의 취소를 존중. timeout은 응답 body 처리까지 포함.
Lead Decision: 수락, v1 계약 유지. Frontend에 전달 완료. 공통 변경은 QA가 근거를 확인하고 Backend Reviewer가 독립 검토합니다.

## Implementation Handoff / Lead 통합

서버 activity_backend는 BibleReaderService, BibleSearchCursorCodec, BibleCursorSearch domain, JpaBibleContentRepository, BibleApi/Controller/Dto 및 단위/통합 테스트를 작성했습니다. 앱 process_review는 BibleSearchScreen/search 모델과 단위 테스트, 기존 BibleScreen 진입, api 함수와 navigation을 작성했습니다. root는 HTTP·PostgreSQL CI, package 검사 목록, 공통 취소와 별도6개 검사, 실제 DB/통합/빌드를 담당했습니다. QA workflow_review는 제품 코드를 수정하지 않고 Playwright 검색 검사를 작성·실행했습니다.

API 추가 외 기존 응답 변경 없음. DB/migration/Event/환경변수/의존성 변경 없음. 기존 lower(text) trigram 인덱스를 재사용합니다. 기록을 읽기만 하며 검색 자체로 읽기 완료/업적을 부여하지 않습니다. 결과 선택 이후 기존 읽기 화면의 기록 정책을 따릅니다.

## Tests / 실제 실행 결과 (2026-10-01)

- Backend focused: codec4 + cursor API5 + 기존 Bible API9. 최초18개 중 테스트의 예상 권 이름에 REV 누락으로1실패, 기대값 보완 후 영향받은API5개 재실행 통과. 제품 응답에는 처음부터 요한계시록이 포함되어 있었습니다.
- Lead `./gradlew test bootJar --no-daemon`: 종료0, 48클래스 228개, 실패/오류/skip0. 원본 `/private/tmp/bible-search-backend-all.log`, XML 보존 `/private/tmp/bible-search-full-test-reports`, 요약 `/private/tmp/bible-search-backend-all-summary.json`.
- Lead `BIBLE_SEARCH_TEST_JDBC_URL=<local PostgreSQL16> ... ./gradlew test --tests '*BibleCursorSearchControllerTests' --no-daemon`: 종료0,5개 실패/오류/skip0. pgvector/pgvector:pg16 격리 컨테이너, Flyway42개 적용 성공, ddl validate. 로그 `/private/tmp/bible-search-postgres-tests.log`, 요약 `/private/tmp/bible-search-postgres-summary.json`.
- Frontend `node --test src/features/bible/search.test.cjs`: 최종Unicode보완 후8/8. `npm run typecheck`: 종료0. root 공통 client 취소6/6. 로그 `/private/tmp/bible-search-app-unit.log`, `/private/tmp/bible-search-app-typecheck.log`, `/private/tmp/bible-search-cancel-tests.log`.
- Lead `npm test`:73/73 실패/skip0. 이후 변경은 search.ts의codepoint/NUL 검증 및 해당8개 검사 보완뿐이며 그8개와타입검사를 다시 수행했습니다. 최종 CI에서 전체 재검증합니다. 로그 `/private/tmp/bible-search-app-all.log`.
- Lead `EXPO_PUBLIC_APP_ENV=prod EXPO_PUBLIC_API_BASE_URL=https://api.example.test npm run build:web`: 최종 변경 후 종료0, PWA45e8bcc6d8068f8a9c4e. 운영 자격증명/환경을 사용한 배포 빌드가 아닌 CI용 설정. 로그 `/private/tmp/bible-search-web-build.log`.
- QA `npm run test:pwa`: 실제 Chromium desktop/mobile 전체26/26 종료0. 이후 입력검증 변경 영향2개 재실행2/2 종료0. 로그 `/private/tmp/bible-search-pwa-qa.log`, `/private/tmp/bible-search-pwa-input-final.log`. 첫/추가 페이지 화면·오류·재시도·경합·권/절 이동 및 기존PWA회귀 포함. 스크린샷 `/private/tmp/bible-search-qa-screenshots-initial`을 Lead와QA가 직접 확인했습니다. 네이티브 실제기기 검증과는 구분합니다.
- 양쪽 `git diff --check`: 종료0.

## 실제 원문 / 성능 확인

Lead가 별도 로컬 PostgreSQL DB에 원문30,991절을 적재하고 bootJar를 Java21로 실행했습니다. `/private/tmp/bible-search-live-check.py`로 실제 HTTP cursor를 마지막까지 따라간 결과:

| 검색어 | 구절 수 | 20개 페이지 수 | SQL 정경 순서와 일치 | 중복 |
| --- | ---: | ---: | --- | ---: |
| 요 | 3,791 | 190 | 일치 | 0 |
| 하나님 | 3,569 | 179 | 일치 | 0 |
| % | 0 | 1 | 일치 | 0 |

`/private/tmp/bible-search-live-summary.json`과 `/private/tmp/bible-search-explain.log`에 근거를 보존했습니다. 한 글자 요는 기존 참조 인덱스+본문 필터, 하나님은 trigram 인덱스를 사용했습니다. 첫/후속 SQL 실행계획 단일 측정은 요 약54~76ms, 하나님 약11ms였으며 이는 로컬 측정으로 운영 부하/SLA 보장이 아닙니다. 전체 HTTP 페이지 측정에서 요 중앙값91ms/p95 107ms, 하나님16ms/p95 29ms였습니다. 새 검색 인덱스는 추가하지 않았습니다.

## QA / Review

- workflow_review: 제품 코드 미작성, 독립 브라우저 QA 및 실행 근거 대조. 실제API 브라우저 desktop/mobile 2/2 및 최종 실행 근거 대조 완료, 미해결 제품 결함 없음.
- activity_backend: 자신이 작성하지 않은 앱·공통 취소 변경 독립 Review. BLOCKER/HIGH/MEDIUM 없음, 검토 범위 MERGE READY. 별도 CI·배포 승인 필요.
- process_review: 자신이 작성하지 않은 서버·HTTP·CI 독립 Review. 실제PG/전체 검사 원본 대조 완료, MERGE READY. 미해결 지적 없음. 대상 제품·테스트·HTTP·CI 11파일 집계 SHA-256 `9aecd5573fcc017056d3e929884076a9cdd5b8978e7124750611565dd539cedd`.
- 검색어 Unicode 경계(앱UTF16 vs서버codepoint)는 앱을 서버 계약에 맞춰 보완하고 관련 검사를 다시 수행했습니다.

## 잔여 상태 / 운영

최종 commit/CI/PR 확인을 진행합니다. 병합/운영 배포/스토어 출시는 하지 않았습니다. main push CI 성공은 staging 자동 배포와 연결되므로 이번 구현 요청만으로 병합하지 않습니다. 서버 API 준비 전 앱을 운영에 공개하지 않습니다. 이전 활동/말씀카드/도전과제 패치와 겹치는 BibleScreen/navigation/package/client는 별도 통합 때 각각 보존해야 합니다.

실제API↔브라우저 QA 추가: 로컬 서버로 API 요청을 전달하는 Playwright route proxy를 사용하고 모의 본문 없이 PostgreSQL 원문을 표시했습니다. desktop/mobile에서20+20개 더보기 후 창세기13:10 본문 이동·화면 내 표시·강조를 확인했습니다. `/private/tmp/bible-search-live-navigation-qa-final.log` 및 `bible-search-live-navigation-{desktop,mobile}-final.png`. 초기 범용 getByText가 숨겨진 검색 결과까지 선택한 strict-mode 실패는 테스트 선택자 문제였으며, 본문 버튼으로 범위를 좁혀 정상 확인했습니다. 실제네이티브/운영/CORS배포 검증을 대신하지 않습니다.
