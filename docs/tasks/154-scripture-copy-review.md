# Independent Review — Scripture Copy MP-154 / APP-121

Reviewer: scripture_review (제품 코드를 작성/수정하지 않음). QA는 별도 scripture_qa.
검토 대상: 서버 `scripture-copy-154` base fbad2e1, 앱 `scripture-copy-121` base 6fb9b6f. 실제 신규 파일/기존 diff와 관련 인증·계정 탈퇴·성경 저장소·내비게이션·서비스 워커 코드를 직접 확인.

## 판정
MERGE READY — 검토 범위의 제품 코드 미해결 BLOCKER/HIGH 없음. 최종 로컬 서버/앱 검사와 실제 PostgreSQL, 독립 browser QA 원본/대상 해시를 확인했습니다. 이 판정은 최종 커밋 CI 성공이나 운영 배포 승인을 대신하지 않습니다. main은 운영 자동 배포에 연결되어 이번 구현 요청 범위에서 병합하지 않습니다.

## Findings
- HIGH — 동일 사용자 새 로그인에서 이전 요청/상태가 재사용됨. 해결 확인: 명시적인 authStore sessionGeneration, refreshSession 분리, 계정 변경 요청 abort/응답 경계 검사, 보호 화면 generation key, session별 Query key.
- HIGH — Native 입력 focus 전체를 composition으로 처리해 자동 저장/완료가 차단됨. 해결 확인: focus는 오타 유예에만 사용하고 draft 자동 저장은 허용.
- HIGH — 상세 Query가 Infinity stale로 저장·완료 후 재진입할 때 이전 DRAFT/revision을 보여줌. 해결 확인: 기존 기록 staleTime 0, gcTime 0, refetchOnMount always.
- HIGH — 다른 기기에서 삭제한 기록의 409→GET404에서 dirty 화면을 나갈 수 없음. 해결 확인: 확인창에서 저장하지 않고 나가기, 진행 중 저장 종료 후 writer stop 및 이탈.
- HIGH — refresh endpoint 무응답 시 writer가 영구 saving 상태. 해결 확인: 공유 refresh fetch에 AbortController와 15초 timeout/finally cleanup.
- MEDIUM — 최초 저장 응답의 canonical snapshot이 입력 화면 원문에 반영되지 않고, 완료 400 거절 후 입력을 영구 잠금. 해결 확인: 응답 sourceText 반영, 확정된 400에서 complete false로 편집 복구.
- MEDIUM — 문장 중간 IME 입력에서도 마지막 문자만 오타 유예. 해결 확인: web composition 중 오류 전체 유예; native selection 위치의 조합 예상 글자 유예.
- MEDIUM — 다른 기기 삭제 후 목록에 캐시 항목 잔존/404 화면에 앱내 돌아가기 없음. QA 독립 발견, Reviewer 실제 코드로 확인. 해결 확인: 목록 재집중 시 refetch, 오류/로딩/선택 없는 화면에 공통 뒤로 이동. 최종 독립 browser QA에서 deleted/conflict discard·재진입 통과.
- LOW — PostgreSQL timestamptz microsecond 정밀도와 최초 Java Instant 응답 차이. 해결 확인: 생성/수정 시 MICROS truncate, 수정 후 actual PostgreSQL와 전체 서버 검사 통과.

## Architecture / Security / Data
Presentation API interface/DTO와 application use case/domain entity/infrastructure repository 경계를 유지. 기존 인증 및 ACCOUNT 동의 gate 재사용. 클라이언트는 위치/입력/묵상만 전송하며 서버 저장소의 원문을 snapshot으로 보존. NFC 후 Unicode L/M/N 비교로 완료를 검증하고 공백/문장부호만 무시. 완료 상태 변경·위치 변경을 거절.

모든 쓰기는 active user 행 잠금→record 조회/쓰기 순서로 AuthService 탈퇴와 직렬화. DB PK/FK/상태/길이/완료 시각 제약 및 owner+updatedAt/id 인덱스 존재. 신규 UUID는 persist/flush로 삽입해 Jpa merge로 타인 기록 덮어쓰기 방지. 동일 본문/상태/위치 retry는 stale revision이어도 같은 revision 반환, 다른 수정은 CAS409. 본인/타인 조회·삭제와 탈퇴 파기 확인. 서버 오류 로그에 개인 원문/묵상 포함하지 않도록 handler 변환. 웹 개인 필사 localStorage/sessionStorage 쓰기 없음, 서비스 워커는 인증/API 요청 우회 및 공개 정적 자산만 보관.

## Evidence independently inspected
서버 `backend-target-final-v423-sha256.json`을 실제 파일 SHA256과 비교: 모두 일치. `backend-postgres-v423-final.xml`: 9 tests / 0 failures / 0 errors / 0 skipped. 원본 system-out에서 Flyway 45 migrations 적용→v42.3 확인. `backend-full-v423-final-xml`: 53 suites / 260 tests / 0 failures / 0 errors / 3 PostgreSQL-only skipped (257 pass). 필사 PG row-lock/계정파기 테스트는 dedicated PG 실행에서 통과. 실행 담당 Backend, Reviewer는 원본/해시를 확인했으며 같은 테스트를 중복 실행하지 않음. 서버/앱 git diff --check 직접 실행 exit 0.

앱 `app-tests-final.log` 원본 집계 113 tests / 113 pass / 0 fail, `app-typecheck-final.log`와 `app-build.log` 확인. 실행 담당 Lead; 최종 typecheck/full test/build 각각 exit0 보고와 원본을 확인. `qa-app-browser-final-target-sha256.json`의 제품 소스·package·browser test·dist 60개 파일 SHA256을 직접 재계산하여 모두 일치. `qa-pwa-full-final.json` 원본의 desktop/mobile 전체 82 expected / 0 unexpected / 0 skipped / 0 flaky, duration 146.301초 확인, 담당 QA 실제 종료 코드0. 신규 필사 16개(각 프로젝트8)는 정상 입력/삭제/합성 composition/줄바꿈/붙여넣기 완료, 느린 저장과 최신 입력 유지, 오류재시도, 충돌확인 취소/불러오기, 로그아웃/재로그인 늦은응답 차단, 실제 완료 상세 재진입 readonly, 오류/원격삭제 탈출, 개인정보 미영속화 등을 포함. 중간에 build 교체와 겹친 SIGINT130 실행은 검증 근거로 사용하지 않음.

QA 생성 mobile screenshot을 Reviewer 직접 확인: 검정 입력/회색 suffix, 정렬, 완성 전 상태 표시와 선택적 묵상 영역 확인. 실제 diff와 해당 서비스/인증/저장소/내비게이션/서비스 워커 실패 조건을 QA 결과와 별도로 판단했으며 Reviewer 제품 파일 수정은 없음. 검토 후 제품 코드 추가 변경 없음. HTTP 실행 예제와 DTO/오류/revision/완료 계약, 신규 disposable PostgreSQL CI step, CR001/002와 운영/마이그레이션 문서 일치 확인.

## Limitations
실제 iOS/Android 기기의 한국어 IME·키보드·커서/선택·긴 본문 overlay는 미실행. Browser synthetic composition/mobile viewport를 native 실제 검증으로 주장하지 않음. Native release 전 실제 기기 확인 필요. Migration 번호는 최신 main/열린 billing PR/실제 배포 순서 재점검 후 v42.3 확정(CR002); 병합 직전 재확인 필요. 서버 배포 선행이며 main 병합은 운영 자동 배포 연결 때문에 이번 승인 범위 밖.
