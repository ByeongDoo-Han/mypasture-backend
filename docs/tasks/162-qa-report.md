# Independent QA — server #162 / app #127

담당 `scripture_qa`. 제품 코드 수정 없이 실제 diff·기능 계약·실패 조건을 검토하고 PWA 테스트만 작성했습니다. 단일 작성 경로는 `tests/pwa/scripture-copy.spec.ts`, 신규 `scripture-copy-range.spec.ts`, Lead 추가 요청에 따른 `ai-history.spec.ts`입니다. 서버 검사 실행 담당은 Lead `/root`이며 QA는 원본 XML과 대상 해시를 독립 확인했습니다.

## 판정

**앱 QA PASS / 서버 QA PASS / 로컬 QA MERGE READY.** 미해결 BLOCKER/HIGH 또는 재현된 기능 결함 없음. 최종 커밋 CI와 운영 반영 상태는 Lead가 별도로 확인해야 합니다. 실제 네이티브 기기와 OS 한글 IME는 미실행이며 웹 mobile 에뮬레이션 PASS로 대체하지 않습니다.

## 대상·결과 재사용

- 작업 base: server `4880f8b356157b56b8c80db1f309fd7da8561b6c`, app `fc95217`.
- PWA 제품 산출물 `58b4f747effe14704f00`; localhost4173 실제 Chromium Desktop Chrome / Pixel7.
- `qa-app-target.json`: 제품 변경 파일·검사 파일·dist 58파일 해시. focused/full 실행 전 보존, full 종료 후 전부 현재 해시 일치.
- full 완료 뒤 AI 모달 캡처의 fade 안정 대기와 animation 비활성화만 수정했습니다. 제품/dist 변경은 0. 변경된 `tests/pwa/ai-history.spec.ts`의 해당 2개를 재검증했고 `qa-app-visual-target.json`에 현재 해시를 보존했습니다. 기존 full 대상과 차이는 이 검사 파일 1개뿐입니다. 변경이 없는 제품 회귀 full은 중복 실행하지 않았습니다.
- 서버 `backend-final-source.json` SHA256 `2a48272e0a8b8a4d7dcb3358880774064ad2a75b7bf4b9d9dd3fcd43e53ef601`. QA 원본 확인 시 모든 대상 파일이 현재 해시와 일치했습니다.

## 직접 실행한 앱 검사

| 범위·명령 | 결과 | 원본 |
| --- | --- | --- |
| `npm run test:pwa -- tests/pwa/scripture-copy.spec.ts tests/pwa/scripture-copy-range.spec.ts tests/pwa/ai-history.spec.ts --reporter=json` | 실제 exit0, 48pass/0fail/0skip/0flaky, 98.321초 | `qa-pwa-focused.json`, `.log`, `qa-pwa-focused-artifacts/` |
| `npm run test:pwa -- --reporter=json` | 실제 exit0, 110pass/0fail/0skip/0flaky, 182.944초 | `qa-pwa-full.json`, `.log`, `qa-pwa-full-artifacts/` |
| `npm run test:pwa -- tests/pwa/ai-history.spec.ts --grep 'deleting history clears' --reporter=json` | 실제 exit0, 2pass/0fail/0skip, 4.631초 | `qa-pwa-visual.json`, `.log`, `qa-pwa-visual-artifacts/` |
| `git diff --check` app | exit0 | QA exec 결과 |

서버·앱 전체 로그는 이 디렉터리의 원본 파일에 보존하며 보고서에는 집계와 근거만 기록했습니다. source/fixture 개인 데이터가 운영 API로 전송되지 않도록 HTTPS 외부 호출은 테스트 route로 격리했습니다.

## 앱 기능 결과

아래 모두 desktop/mobile PASS입니다.

- 기존 한 절은 범위 선택 기본 1절 → 명시적 필사 시작 → 빈 DRAFT 먼저 저장 후 입력 화면 진입. 최초 UUID/expectedRevision0/endVerse1 확인.
- 같은 장 최대20절 선택, 21절 미노출, 중간 누락 절에서 선택 중단. 원문은 서버 응답 snapshot으로 확인하며 client preview의 다른 본문을 편집기 원문으로 사용하지 않음.
- 전체 범위 제목/목록 metadata/endVerse 유지, 자동 저장, 기록 ID 재개, 전체 실질 문자 완료, 본문 readonly. 같은 절을 다시 시작할 때 새 draft UUID/빈 입력으로 초기화.
- 최초 저장503 및 저장 뒤 응답 유실 retry에서 같은 UUID를 사용하고 중복 기록/revision 없이 멱등 복구. 첫 시도 뒤 범위 잠금 확인. old server가 endVerse를 무시한 응답은 입력 화면을 열지 않음.
- 완료 후 묵상은 자동 저장하지 않고 명시적 PATCH. PATCH payload reflection/revision만 포함, 저장503/retry와409 시 현재 입력 보존, 명시적 reload 취소/확인과 최신 revision 재저장. 원문·작성 본문·완료 시각·범위·status 불변.
- 지연 A 생성 응답은 로그아웃 후 B editor/쓰기 경로에 반영되지 않음. 기존 single의 지연 목록 계정 격리와 미인증 gate도 회귀 PASS.
- 회색 원문/검정 입력/오타/삭제/실제 browser clipboard 붙여넣기/합성 한글 composition/문장 중간 composition, 완료/reopen/삭제 및 충돌 복구, 개인 기록 browser durable storage 미저장 회귀 PASS.
- AI 이력 삭제 모달의 현재 상담 문맥 초기화 안내. 삭제 성공 뒤 현재 conversation/turns 제거, 작성 중 질문 보존, 다음 요청에 이전 conversationId 미전송. ask·regeneration 각각 진행 중 삭제 비활성화, 완료 후 활성화. 기존 이력 페이지/권한/동의/삭제 실패 retry 회귀 PASS.

## 화면 확인

QA가 실제 PNG를 열어 desktop/mobile의 검정/회색 분리·줄 정렬·가로 overflow와 완료 묵상 편집 화면을 확인했습니다. AI 모달 첫 캡처는 fade 중간 프레임이었으며 제품 결함으로 판정하지 않고 안정 대기 후 재캡처하여 불투명 안내·버튼을 확인했습니다. Lead도 주요 화면을 별도로 열었습니다.

- `qa-pwa-focused-artifacts/scripture-copy-range-twent-3740a--complete-the-whole-passage-desktop/range-twenty-writing.png`
- `qa-pwa-focused-artifacts/scripture-copy-range-twent-3740a--complete-the-whole-passage-mobile/range-twenty-writing.png`
- `qa-pwa-focused-artifacts/scripture-copy-range-compl-c78ad-ext-and-completion-metadata-desktop/range-completed-reflection.png`
- `qa-pwa-focused-artifacts/scripture-copy-range-compl-c78ad-ext-and-completion-metadata-mobile/range-completed-reflection.png`
- `qa-pwa-visual-artifacts/ai-history-deleting-histor-b9100-eserving-an-unsent-question-desktop/history-delete-context-warning.png`
- `qa-pwa-visual-artifacts/ai-history-deleting-histor-b9100-eserving-an-unsent-question-mobile/history-delete-context-warning.png`

## Lead 실행 근거의 독립 확인 — QA 재실행 없음

앱 Lead 명령 `npm run typecheck`, `npm test`, `EXPO_NO_DOTENV=1 EXPO_PUBLIC_API_BASE_URL=https://api.example.test npm run build:web`, `npm run test:hosting` 모두 실제 exit0를 Lead가 확인했습니다. QA는 `app-unit-final.log` 원본 119pass/0fail/0skip, 1265.449ms와 실행 대상 manifest를 확인했습니다. 다른 검사의 count/time은 집계되지 않아 추정하지 않습니다. 원본 `app-{typecheck,unit,build,hosting}-final.log`.

서버 환경: JDK/Gradle 프로젝트 표준 경로, 실제 PG16 pgvector Docker127.0.0.1:15462 임시 DB `next162`. DB 인증 값은 기록하지 않습니다.

| 범위·명령 | 결과 및 독립 확인 | 원본 |
| --- | --- | --- |
| initial `./gradlew test --tests '*AiContextSnapshot*' --tests '*AiQuestionScopeFlowTests' --tests '*AiAnswerHistoryTests' --tests '*AiAssistantFlowTests' --tests '*AiSseTestSupportTests' --tests '*AiControllerStreamingTests' --tests '*AiHistoryControllerTests' --tests '*ScriptureCopyControllerTests' --no-daemon` | 81tests/1fail/4skip. snapshot-write rollback 검사에서 Spring 예외 wrapper의 예상 타입 불일치. 테스트만 wrapper+원인 검증으로 수정, rollback 조건은 유지. 실패를 PASS로 사용하지 않음 | `backend-narrow-first-xml/`, `backend-narrow.log` |
| fixed `./gradlew test --tests '*AiContextSnapshot*' --no-daemon` | Lead exit0/29초, QA XML 2suites14tests/0fail/0error/1PGskip | `backend-context-fixed-xml/` |
| PG `./gradlew test --tests '*AiContextSnapshot*' --tests '*AiContextDeletionGuardPostgresTests' --tests '*AiAnswerHistoryTests' --tests '*AiHistoryControllerTests' --tests '*ScriptureCopyControllerTests' --no-daemon` with AI_HISTORY_TEST_JDBC_* / SCRIPTURE_COPY_TEST_JDBC_* | Lead exit0/46초, QA XML 6suites50pass/0fail/0error/0skip | `backend-postgres-xml/`, `backend-postgres.log`, `backend-postgres-counts.json` |
| full `./gradlew test --no-daemon` | Lead exit0/110초, QA XML 59suites313tests/303pass/0fail/0error/10PGskip | `backend-full-xml/`, `backend-full.log`, `backend-full-counts.json` |

full의 skipped 10개 모두 동일 classname+testname을 실제 PG XML과 대조하여 실제 PASS임을 확인했습니다. 깨끗한 Flyway와42.3→42.4/42.5 upgrade/backfill, 20절/UTF16 원문 bounds, owner/legacy end 호환, completed PATCH retry/race/delete/탈퇴, row lock fencing, AI owner/turn AAD·불변 당시 문맥·30일 고정 만료·legacy fail-closed·전 그룹 snapshot 파기와 다른 답변 보존·생성 뒤 삭제/계정 삭제 fencing/transaction rollback을 독립 읽고 검사 근거를 확인했습니다.

PG 삭제 guard 6개는 실제 SQL의 다중 owner/0-row/cascade, NOWAIT55P03 rollback/retry, deadlock40P01 victim rollback, 기존 데이터 보존/DB constraint를 확인했습니다. SSE helper의 종료 frame/MVC/runner shared deadline과 누락 event/미완료 negative 조건, 무작위 traceId의 bad substring 충돌과 계약 필드 누출 negative 회귀도 독립 검토했습니다.

## 제한·미실행·역할 구분

- 실제 iOS/Android 기기, OS 한글 IME, 실제 네이티브 키보드/긴 범위 레이아웃: 기기 환경 없음, 미실행. 합성 Chromium composition/Pixel7 에뮬레이션과 구분.
- QA PWA는 외부 API fixture를 사용하며 실제 PostgreSQL 검증을 대체하지 않습니다. DB/API는 위 실제 서버 원본 검사로 별도 확인했습니다.
- 실제 모델 표본/운영 배포·공개 smoke·최종 CI·PR/병합은 Lead 담당이며 이 로컬 QA 결과만으로 완료를 주장하지 않습니다.
- task 문서와 제품 수정은 Lead 단일 작성, 별도 독립 Review는 `scripture_review` 담당입니다. 이 보고서는 QA를 수행한 범위만 기록합니다.

## Lead 실제 모델 표본 검사 근거

Lead 명령 `./gradlew openAiSmokeTest --tests '*AiQuestionScopeLiveTests' --no-daemon` 실제 exit0/24초. QA는 `build/test-results/openAiSmokeTest/TEST-com.example.mypasture.ai.AiQuestionScopeLiveTests.xml`의 **1test/0fail/0error/0skip**을 확인했습니다. 이 1test 안의 합성 표본7개를 Lead가 모두 PASS로 보고했습니다. 가격 질문/말씀 위로/종교 비교/모호한 질문/prompt injection/문맥 속 짧은 동의/가격 주제 전환을 포함하며 전체 모델 정확도 증명이 아닙니다. 일반 CI에는 실제 모델 호출을 포함하지 않습니다. 원본 `ai-scope-live.log`와 위 XML. 토큰·추정 비용은 Lead 실행 집계이며 QA가 가격을 별도로 계산하거나 검증하지 않았습니다.
