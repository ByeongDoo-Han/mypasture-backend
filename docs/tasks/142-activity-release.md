# 활동 잔디 운영 공개 / 142

## Requirement
사용자가 서버 PR143 최종 검증·병합·운영 배포 후 웹 잔디 활성화·재배포를 승인했다. 스토어 출시는 제외한다. 기존 기록 정확성/계정 격리 결함은 공개 전에 보완한다.

## Existing System
서버 PR143 cd6d1f0, 최신 main9513014를 충돌 없이 통합했다. 앱 main0cc7f7c에는 UI가 있으나 공개 설정 false이다. 기존 별도 main-2 작업폴더의 activity-integration-142 기록/patch는 미적용이며 CR001 사용자별 읽기 직렬화, CR002 요청·캐시·refresh 격리 수정과 테스트가 준비되어 있다. 원본 사용자 작업은 변경하지 않는다.

## Impact / Work Breakdown
규모 Large: 인증/읽기/DB/두 저장소/운영 배포의 통합.
- Lead root: 계획·계약, 공유 CI/http/migration/문서, 앱 코드 통합과 설정, 테스트 환경, 최종 배포. 앱 Issue112는 최신main의 별도 activity-release-112 worktree 사용.
- Implementation activity_backend: 서버 ReadingService 및 ReadingActivityIntegrationTests, 해당 기능 실패 수정. 서버 기능/테스트 외 공유 경로는 root에게 요청.
- QA process_review: 실제 diff/검사 근거, 앱 브라우저/계정격리 및 API 통합 QA. 제품 코드 수정 없음.
- Reviewer workflow_review: 앱/서버 독립 보안·트랜잭션·migration·계약 검토. 제품 코드 수정 없음.

## Contracts
확정 v1, Lead root.
API: JWT 본인 전용 PUT /api/v1/activity/today(본문없음, date 반환, 동일일 멱등), GET /api/v1/activity?year(2000..KST현재연도, year/timezone/today/days). days는 date/visited/chaptersRead/chaptersCompleted. 무인증401, 잘못된연도400, 타인ID입력없음. 기존 읽기 및 검색 API 호환 유지.
DB: V42.1 user_visits(id UUID PK,user_id FK,visited_on DATE,created_at TIMESTAMPTZ), unique(user_id,visited_on). 기존 방문 backfill없음, 읽기기록 재사용. 최신main 및 운영적용버전42 확인 후 적용; 열린 결제PR135의43/44 미적용 확인. 새적용이므로 롤백은 이전이미지/웹flagfalse, 테이블 보존; migration파일수정/out-of-order없음.
Event: 없음.
Interface: CR001 읽기 state/history조회전 사용자행잠금, 트랜잭션안에서 직렬화. CR002 읽기 함수 expectedUserId와 계정별query key, 계정전환시 늦은응답/재전송거부. refresh는동일userId+refreshToken에만공유. 검색의AbortSignal/본문timeout보완유지.
Configuration: 서버 새환경변수없음. 웹 EXPO_PUBLIC_ACTIVITY_ENABLED=true는 서버 배포/준비 후 활성화; 웹결제false, Kakao비활성, API/Google공개값유지.

## Dependencies / Risks
서버/앱 안전수정은 병렬 가능. 순서는 통합수정→단위/전체·실제PG/Flyway→독립QA/Review→최종CI→서버병합/자동배포/DB확인→웹main빌드/Pages업로드→운영검증. 계정격리와 데이터동시성은 필수조건. 운영인증정보를 로그에 남기지 않는다. 새라이브러리/패턴 없음.

## Definition of Done / 검증
- [ ] 담당·전체서버검사, 실제PG/Flyway·중복/동시읽기 검증
- [ ] 앱계정격리단위/typecheck/build/hosting/PWA, desktop/mobile 화면
- [ ] 독립QA/Review의 미해결BLOCKER/HIGH없음
- [ ] 최종CI 및 server/image/DB준비 확인
- [ ] 웹프로덕션SHA/잔디활성화/핵심흐름 확인, 실제native미검증 구분

테스트 실행 담당: backend가서버담당/전체/PG를순차실행하며 root가docker제공. root는앱단위/타입/build/hosting, QA는브라우저. 각로그는/private/tmp/activity-release-*로보존하고요약만공유. 소스/환경이일치하는성공근거재사용.

## Migration 사전 확인
2026-10-01 공개API에 연결된 my-pasture/asia-northeast3/mypasture-staging-migration의 최근실행 로그 확인: 09:38:18 UTC current version42, validated42, up to date. main9513014 및 결제PR135 OPEN(43/44)와 대조. V42.1을 유지하며 병합직전 새배포 여부 재확인한다.

## Change Requests / Lead 결정
- CR001/002 수락: 이전 검토의 미반영 HIGH(읽기동시성, 계정간캐시/401재전송)와 MEDIUM(refresh합류)를 현행main에 통합한다. 앱검색 취소/본문처리timeout코드는 유지한다. 기존인터페이스/DB/API응답 변경없음.
- CR003 activity_backend 요청/Root 수락: 실제 담당테스트8개중1개가 범위밖절 입력에400대신500 재현. reading/presentation/ReadingExceptionHandler.kt로 ReadingController의 IllegalArgumentException/DateTimeException만 INVALID_REQUEST400 매핑. Global/인증예외에는 영향없음. 잘못된timezone rollback도검증한다.
- CR004 QA process_review 발견/Root 수락: react-native-web Alert.alert가no-op이라 기존웹로그아웃이작동하지않음. MoreScreen의웹에서window.confirm후기존performSignOut을호출, 네이티브Alert유지. 사용자계정전환검증에필요. QA가확인/취소및계정변경을검증한다.

## 인수인계 / 앱
Root가 mobile/activity-release-112에서 이전patch를현재검색코드에맞게통합. Home/Bible읽기키에userId, GET/PUTexpectedUserId, 화면선택의userId초기화, 계정/token별refresh공유. 테스트에현행search모듈의존성추가. .env.web.example/.env.web는true(서버준비전업로드금지), 공개설정외변경없음. QA는tests/pwa/activity.spec.ts와activity-isolation.spec.ts의단일작성자이다.

- CR005 Root 수락/작성, Reviewer 동의: 전체표준테스트에서512MiB heap이포화되어 실제OutOfMemoryError로활동context로드실패. 일반test task의maxHeapSize=1g로확대한다. 운영JVM/의존성/테스트선택변경없음. 실패실행과진단로그보존후동일표준명령전체·PG를재실행한다.

## 검증 중간 결과
- activity_backend: 담당 H2 9/9, CR003 실제400/롤백·동시읽기검증. 전체test bootJar는초기512MiB OOM 실패(로그보존), CR005 1g후정식명령237/237·50suites·실패/오류/skip0·2분25초·종료0. /private/tmp/activity-release-server-all.log 및 -all-results.
- Root: 앱계정격리/취소담당14개 및전체86/86, typecheck, prod빌드·hosting통과. 최신PWA a1b5c14decc872ee6d12. 공개설정true는서버준비전업로드하지않음.
- CR006 process_review QA와 workflow_review가HIGH실제재현: A검색GEN13→activity401/refresh401자동로그아웃→B로그인에서B토큰에GEN13읽기PUT발생. Root는계정변경시기존route.params.requestId를소비한값으로보존해재실행을차단. Reviewer정적확인, desktop/mobile회귀통과. 새로운명시적검색이동유지. 이전실패근거/private/tmp/activity-release-bug-stale-route.

## 최종 로컬 QA / Review
- 실제 PostgreSQL16.15: 담당두클래스9/9, test실제실행·종료0·33초. 빈DB43개migration적용→42.1, SQLhistory success=true. /private/tmp/activity-release-server-postgres.log 및 -postgres-results.
- Reviewer workflow_review가 원래PR143전체와CR보완·XML/PG로그·diff를독립검토: 서버 MERGE READY, 미해결BLOCKER/HIGH/MEDIUM없음. trackeddiff(base9513014)식별9308872f60bc6167b863180a4160163c3dad0cd35d3904f635bd3fadd1a83d79, Advice6a8f9c0d, IntegrationTestca325022.
- QA process_review: 실제Chromium desktop/mobile전체46/46, fixture정확성보완후해당4/4. 로그인/읽기/완독·KST자정·연도/윤년·재시도·계정격리·검색/PWA회귀통과. 운영네트워크모의이며실계정검증과구분. Root최종typecheck는fixture수정후통과.
- 서버bootJar는전체최종실행에서UP-TO-DATE(직전동일제품코드실행에서빌드완료). 테스트heap변경은운영산출물에영향없음. CIcontainer-build로최종커밋이미지다시검증한다.
- 서버·앱제품소스검증후동결. 최종CI/병합/운영검증은PR에추가해기록한다.
