# Independent Review — Legacy Scripture INSERT (#164)

- Reviewer: `/root/scripture_review`. 구현자 `/root` 및 QA `/root/scripture_qa`와 분리. 제품·migration·CI·테스트 수정 없음.
- 대상: server `next-steps-162`, `fix/bible-164-legacy-insert`, base `6de1632`. 계약 `docs/tasks/164-legacy-scripture-insert.md`.
- 대상 manifest: `legacy-copy-source.json`, 383파일, SHA256 `554a9dbab09e7de6cdf24c21b263f9b987e53903993fe8ab21f048b0dc1ec26f`. 실제 파일 전부 일치 확인.
- 최종 판정: **MERGE READY**. #164/제약/호환 및 #165/CR005를 포함한 최종 diff·관련 코드·같은 소스의 필수 검사 원본을 확인했다. 미해결 BLOCKER/HIGH 및 필수 로컬 검증 누락 없음. 최종 CI/42.6 운영 적용은 Lead의 별도 완료 조건이다.

## 실제 코드 검토

V42.6은 BEFORE INSERT ROW trigger에서 `NEW.end_verse IS NULL`일 때만 `NEW.verse`를 대입한다. 구버전 서버의 컬럼 생략 INSERT와 명시적 NULL은 기존 한 절 의미로 저장되며, 명시적 연속 범위는 그대로 남는다. 기존 V42.5를 수정하지 않고 새 migration을 추가했다. UPDATE/DELETE/기존 행은 변경하지 않으며 NOT NULL·양수·시작 이상·최대20절 제약도 유지한다.

함수는 기본 invoker이고 SECURITY DEFINER나 권한 확장이 없다. 다른 행/테이블 조회·추가 잠금·외부 호출이 없으며 새 동시성 순서나 교착 경로를 만들지 않는다. 기존 user FK·상태·소유권·원문·입력·묵상·revision·시각은 그대로다. migration42.6은 현재42.5 뒤에서 적용되고 out-of-order가 필요 없다.

CI 변경은 기존 PostgreSQL ScriptureCopy 단계에 새 regression class selector를 추가하는 한 줄이다. 기존 전체 job·PostgreSQL·container checks를 유지한다. 신규 PG test의 schema 이름은 UUID로 생성하며 Flyway clean도 해당 격리 schema에만 적용된다. JDBC 연결·statement/result set은 use/finally로 해제한다. 사용자 데이터/비밀 로그 노출을 추가하지 않는다.

운영 API/worker Terraform은 SPRING_FLYWAY_ENABLED=false이고 별도 migration job만 true임을 확인했다. 이는 구 API image 자체의 startup Flyway 검증 문제와 이번 SQL INSERT 호환 문제를 구분하는 근거다. Reviewer가 구 Docker image 전체를 직접 실행하거나 운영 smoke를 수행한 것은 아니다.

## 검증 원본 확인

실행자는 root이고 Reviewer는 같은 검사를 재실행하지 않고 실제 diff·관련 원본·XML·manifest를 독립 확인했다.

| 검사 | 원본과 실제 결과 | 판정 |
| --- | --- | --- |
| 담당/인접 실제 PostgreSQL | `legacy-copy-pg.log`, `legacy-copy-pg-xml`, `legacy-copy-pg-counts.json`; root 보고 exit0/35초, XML 18개(기존 controller16 + 신규2), 실패0/건너뜀0 | PASS |
| migration/기존 데이터/호환 | 실제42.5에서 legacy omitted INSERT23502 재현→42.6 upgrade 성공, 기존range2..5/원문/입력/묵상/revision7/DRAFT 보존. clean42.6 omitted7→7, 명시NULL→한 절, 명시range/최대20절 보존, end0/21→23514, UPDATE NULL→23502 유지 | PASS |
| 전체 첫 실행(실패 보존) | `legacy-copy-full-first-xml`, `legacy-copy-full.log`;315개, 실패1, 건너뜀12. AiControllerStreamingTests scope error 종료 테스트의 PrintingResultHandler header 순회 ConcurrentModificationException | FAIL, 최종 실행과 구분 |
| CR005 SSE/helper 좁은 검사 | `legacy-sse-fixed-xml/log/counts.json`;13개 통과/실패0/skip0,32초 | PASS |
| 최종 전체 H2 | `legacy-copy-full-fixed-xml/log/counts.json`;60 suites315개 중303통과/실패0/12 PG-only skip,109초 | PASS |
| 최종 모든 PostgreSQL | `legacy-copy-postgres-all-xml/log/counts.json`;7 suites52개 통과/실패0/skip0,45초. 전체 H2 skip12개의 classname+testname 모두 동일 PostgreSQL 실행에서 PASS 대조 | PASS |
| diff whitespace | Reviewer `git diff --check` exit0 | PASS |
| 최종 CI / 운영 migration42.6 | Lead 후속 단계 | 별도 확인 필요 |

## 지적과 해결 상태

- MEDIUM — 롤링/old-image 구 SQL INSERT 누락: V42.5 NOT NULL로 end_verse 생략이23502였다. 이번 V42.6과 실제 업그레이드 regression이 직접 해결함. 코드 검토상 resolved.
- MEDIUM — 필수 전체 검사 실패: 실패 stack은 응답/SSE의 최종 기능 assertion 전 MockMvc의 자동 실패 출력이 비동기 headers를 순회한 경합이다. 기존 ScriptureCopy 테스트와 같은 `MockMvcPrint.NONE` test-only 적용이 최소안이며, completion/required events/runner idle assertion은 유지되어야 한다. 제품 assertion 완화나 skip은 허용하지 않음. CR005 실제 diff에서 MockMvcPrint import와 `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)` 2줄만 변경됨을 확인했다. 모든 assertion/timeout/제품은 불변이며 코드 수정은 승인. 동일 최종 소스의 SSE/helper13·전체 H2315·현재 migration42.6 PostgreSQL52 실제 재검증에서 실패0으로 해결 확인했다.
- 추가 BLOCKER/HIGH/LOW/SUGGESTION 없음. API 변경은 없으므로 DTO/OpenAPI/http 계약 수정은 비해당.

MERGE READY는 필수 최종 CI나 운영 배포 완료의 증명이 아니다. 서버 migration42.6 선행 적용과 실제 준비 상태/호환 smoke를 Lead가 확인한 뒤 앱 웹 공개 순서를 유지한다.

## CR005 변경 후 재검토

#165의 원인은 자동 실패 출력과 비동기 응답 header 수정의 경합이다. 기존 필사 테스트 패턴을 재사용한 print NONE은 출력만 비활성화하며 SSE frame/terminal/필수 이벤트/runner idle 검증은 그대로 수행한다. 기존 첫 PG18 통과는 변경 전 원본으로 별도 보존하고 최종 소스에서는 SSE/helper 좁은 검사·전체 H2·전체 현재 PG52를 순서대로 다시 확인했다. `git diff --check` exit0 및 최종383파일 해시 일치를 확인했다.

## 최종 원본 인수 확인

Root 실행 session87145 최종 exit0을 인수했다. 좁은 Gradle SSE/controller/helper 선택 실행, 전체 Gradle test, 현재 PostgreSQL AI/Scripture controller·snapshot/history/삭제guard·legacy INSERT 7개 suite 선택 실행의 원본을 확인했다. 실행 범위는 원본 XML로 대조했고 Lead의 정확한 실행 커맨드를 아래에 인수했다. 환경은 프로젝트 표준 Gradle/Java17 및 disposable PostgreSQL이며 실제PG env가 켜진 별도 실행52개에서 skipped0이다. Reviewer는 직접 테스트를 중복 실행하지 않았고 XML·counts·로그·해시·원본 diff를 독립 대조했다.

최종383파일 manifest SHA256 `554a9dbab09e7de6cdf24c21b263f9b987e53903993fe8ab21f048b0dc1ec26f` 전부 현재 내용과 일치하며 Reviewer 최종 `git diff --check` exit0. 앞선 full 실패는 보존했고 통과로 재분류하지 않았다. CR005는 필수 기능 assertion을 유지한 채 자동 진단 출력 경합만 제거했다. 현재 **MERGE READY**이며 후속 서버 최종 CI와 운영42.6 적용/준비상태 확인 뒤 앱 공개라는 순서를 유지한다. 앞선 feature6de의 운영READY는 이 followup42.6 적용 완료의 대체 근거가 아니다.

## 실제 실행 명령

```sh
./gradlew test --tests '*AiControllerStreamingTests' --tests '*AiSseTestSupportTests' --no-daemon
./gradlew test --no-daemon
./gradlew test --tests '*AiContextSnapshot*' --tests '*AiContextDeletionGuardPostgresTests' --tests '*AiAnswerHistoryTests' --tests '*AiHistoryControllerTests' --tests '*ScriptureCopyControllerTests' --tests '*ScriptureCopyLegacyInsertPostgresTests' --no-daemon
```

세 번째 실행만 disposable PostgreSQL의 `AI_HISTORY_TEST_JDBC_{URL,USER,PASSWORD}`와 `SCRIPTURE_COPY_TEST_JDBC_{URL,USER,PASSWORD}`를 설정했다. 비밀값은 보고서에 기록하지 않는다. 초기 PG18개는 ScriptureCopyControllerTests 및 ScriptureCopyLegacyInsertPostgresTests만 선택한 실행이다. 현재 결과 원본에 실제 testcase가 생성됐음을 확인했으며 UP-TO-DATE/0개 발견을 통과로 처리하지 않았다.
