---
name: mypasture-api-contract
description: Coordinate a My Pasture backend API contract change and hand it to mobile or admin consumers when endpoints, responses, errors, or authorization behavior change.
---

# 서버 API 계약과 소비자 인수

API 계약을 바꾸기 전에 [서버 규칙](../../../AGENTS.md), 해당 `*Api` interface·Controller, [API 명세](../../../docs/reference/api-spec.md), 관련 `http/*.http`와 실제 호출자를 확인한다. 이 스킬은 서버 계약 변경과 소비자 인수를 다룬다. 실행 검사는 `mypasture-change-verification`과 저장소 규칙을 따른다.

1. 변경 전후의 경로·method, 요청·응답 DTO, 상태·오류 코드, 인증·객체 소유권, 페이지네이션·기본값을 표로 정리한다. 기존 배포 앱과 관리자가 사용할 수 있는지 판단하고 미확인 사항은 명시한다.
2. 계약의 단일 작성자를 지정한다. 서버 `*Api`, Controller, DTO, `.http` 예제와 필요한 명세를 같은 변경에서 맞춘다. Entity를 응답으로 노출하지 않는다. 모바일·관리자 작업은 확정된 서버 커밋 또는 계약 문서를 기준으로 인계한다.
3. 각 소비자에 필요한 타입·화면 상태·오류 처리와 선행 배포 조건을 Issue/PR 또는 [작업 기록](../../../docs/tasks/TEMPLATE.md)에 연결한다. 소비자 저장소에 수정이 없으면 이유를 적는다.
4. 정상·입력 실패·인증/권한·객체 소유권·구버전 호환 흐름을 변경 영향에 맞게 검증한다. 최종 인수에는 서버 커밋, API 예제, 검사 결과, 관련 모바일·관리자 Issue/PR과 남은 위험을 포함한다.

다른 담당자의 저장소나 공유 파일을 수정할 때는 해당 작업 계약의 Owner·허용 경로를 먼저 확인한다. 문서만으로 클라이언트 연동이나 운영 적용이 완료됐다고 표시하지 않는다.
