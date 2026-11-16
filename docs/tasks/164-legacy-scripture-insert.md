# 구버전 필사 INSERT 호환 보완 (#164)

## 요구사항/현황/규모
사용자가 승인한 연속 필사·운영 반영의 롤백 호환 보완입니다. main6de1632, 운영 migration42.5와 새 서버 image의 배포가 성공했습니다. PostgreSQL42.5에서 이전 서버의 end_verse 없는 INSERT가23502를 반환함을 재현했습니다. 새 서버는 명시적 end_verse를 저장해 정상이며, 롤링/이전 image 롤백의 쓰기 경계가 누락됐습니다. 적용된 V42.5는 수정하지 않습니다.

Medium, 검증 고위험(DB/migration): Lead/구현 /root, 독립 QA scripture_qa, 별도 Reviewer scripture_review. Root만 migration/CI/새 PG테스트/이 문서를 작성합니다. QA/Reviewer는 읽기·원본 확인·보고서만 작성합니다. 기존 사용자 worktree/main-2는 변경하지 않습니다. 서버를 선행 보완한 뒤 앱 웹을 공개합니다.

## 계약/영향/작업
- V42.6 새 migration으로 scripture_copies BEFORE INSERT 트리거를 추가합니다. NEW.end_verse IS NULL일 때만 NEW.verse를 대입합니다.
- 명시적 end_verse, source_text, typed_text, reflection, revision, 상태/시각/소유권은 변경하지 않습니다. 기존 range constraint와 NOT NULL을 유지합니다. UPDATE/DELETE에는 동작하지 않습니다.
- API/DB 컬럼/DTO/Event/환경변수/의존성 변경 없음. 함수는 invoker이며 권한을 확장하지 않습니다. 다른 테이블을 조회하거나 lock을 추가하지 않습니다.
- 최신main 최대42.5, 실제운영42.5, 열린 billing43/44를 확인해42.6을 선택합니다. 적용migration 수정/out-of-order를 사용하지 않습니다.

## 검증/완료
- [x] 실제 PostgreSQL42.5에서 legacy INSERT 실패 재현 후42.6 upgrade 성공/기존 범위·입력 보존.
- [x] clean latest legacy omitted/null fallback, 명시적 최대20절 보존, 잘못된 범위23514 유지.
- [x] 담당·인접 PostgreSQL 및 서버 로컬 전체, diff --check.
- [x] 구현하지 않은 QA/Reviewer의 실제 diff·원본 확인, 미해결BLOCKER/HIGH0.
- [ ] 최종CI/main/운영42.6 적용/동일image/ready 확인.

실행 담당 root. 전체 로그/XML은 `/private/tmp/mypasture-next-steps-20261007/`에 보존하며 종료 코드/실제 수/환경/대상hash를 기록합니다. 앱 코드·의존성은 불변이므로 PR128 최종CI와 local 검증 결과를 재사용하고 main 자동CI는 유지합니다. 구버전 INSERT가 필사 범위를 모르므로 묵시적 필사는 한 절이 되는 기존 의미를 유지합니다.

## CR-005 / SSE 테스트 출력 경합 보완

첫 로컬 전체315개 중1개가 MockMvc PrintingResultHandler.getResponseHeaders의 ConcurrentModificationException으로 실패했습니다. streaming 오류 응답의 header 변경과 기본 진단 프린터의 iteration이 겹치며 SSE helper 호출 이전에 실패하는 경합을 원본 stack과 QA·Reviewer가 확인했습니다.

Root 수락: 해당 AiControllerStreamingTests class에 기존 필사 검사와 같은 MockMvcPrint.NONE만 적용합니다. 허용 경로에 이 테스트1파일을 추가하며 root가 단일 작성자입니다. 제품/timeout/SSE event/status/receipt/ownership assertions와 다른 테스트의 출력 설정은 변경하지 않습니다. Small tests-only 수정이며 실제 담당 helper/streaming 후 서버 전체와 실제PG를 재검증합니다. 첫 실패 결과는 보존하고 최종 통과로 재사용하지 않습니다.

## 최종 로컬 검증

대상383파일 manifest `554a9dbab09e7de6cdf24c21b263f9b987e53903993fe8ab21f048b0dc1ec26f`, 실행 담당 root, JDK17/Gradle9.7.1 및 폐기 가능한 PostgreSQL16 pgvector Docker입니다.

| 검사 | 실제 결과 | 원본 |
| --- | --- | --- |
| PG 최초 담당·인접 |18통과/실패0/skip0, exit0/35초 | legacy-copy-pg-xml/log |
| 전체 첫 실행 |315개/실패1/PG전용skip12, exit1/111초. MockMvc response printer의 ConcurrentModificationException. 원본보존, 통과로재사용하지않음 | legacy-copy-full-first-xml/log |
| 수정 SSE/helper |13통과/실패0/skip0, exit0/32초 | legacy-sse-fixed-xml/log |
| 수정 전체 |315개/303통과/실패0/PG전용skip12, exit0/109초 | legacy-copy-full-fixed-xml/log |
| 현재42.6 모든 관련PG |52통과/실패0/skip0, exit0/45초. skip12 동일testcase를 QA·Reviewer가 모두PG PASS와대조 | legacy-copy-postgres-all-xml/log |

독립 QA scripture_qa와 Reviewer scripture_review는 actual diff/SQL/동일소스 해시/명령/원본을 확인하며 제품을 수정하지 않았습니다. API·SDK·앱소스/설정은 불변이므로 앱 local119/PWA110 및 PR128 CI37648611192/main9bce830 CI37650260159의 성공을 재사용합니다. 새로운 영향·실패 때문에 필요한 SSE/전체/PG를 다시검사했으며 기존실패를누락하거나재시도만으로숨기지않았습니다.

현재 feature main6de1632의 운영API47/worker40 Ready·image digest가해당commit과일치·traffic100%·publicreadinessUP/66책을 확인했습니다. 새42.6 후속PR의 최종CI/main/배포는 별도 완료조건입니다.
