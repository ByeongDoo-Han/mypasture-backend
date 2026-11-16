# 성경 필사

로그인한 사용자가 성경의 한 절을 직접 입력하고 개인 기록으로 보관합니다. 원문은 서버가 확인한 본문이며 최초 저장 시 snapshot으로 고정합니다. 사용자의 원문 문자열이나 완료 여부를 그대로 믿지 않습니다.

## API

계약은 `ScriptureCopyApi`, 실행 예제는 [scripture-copies.http](../http/scripture-copies.http)에 있습니다. 모두 JWT 본인 대상입니다.

| Method / 경로 | 용도 |
| --- | --- |
| PUT `/api/v1/scripture-copies/{id}` | client UUID로 임시 저장 또는 완료 |
| GET `/api/v1/scripture-copies?page=0&size=20` | 수정 최신순 목록 |
| GET `/api/v1/scripture-copies/{id}` | 상세 / 이어 쓰기 |
| DELETE `/api/v1/scripture-copies/{id}?expectedRevision=N` | 확인한 revision의 기록 삭제 |

PUT 필드는 version, bookCode, chapter, verse, typedText, reflection, expectedRevision, complete입니다. 신규 expectedRevision은 0이며 최초 응답 revision은 1입니다. 같은 위치·내용·완료 상태의 재요청은 revision을 증가시키지 않습니다. 다른 내용의 오래된 revision은 409입니다. 완료된 내용은 변경할 수 없고 같은 절을 다시 필사하려면 새 UUID로 시작합니다.

응답은 id, version, bookCode, bookName, chapter, verse, sourceText, typedText, reflection, revision, status, createdAt, updatedAt, completedAt입니다. status는 DRAFT 또는 COMPLETED입니다. 목록은 items, page, size, totalElements, totalPages를 포함하며 size는 1~50입니다.

완료는 NFC 정규화 후 Unicode 문자·결합문자·숫자만 비교합니다. 공백, 문장부호와 줄바꿈은 무시하고, 누락·추가·다른 글자는 허용하지 않습니다. 비교할 글자가 없는 원문은 완료할 수 없습니다. typedText는 최대 10,000 UTF-16 단위, reflection은 최대 1,000 UTF-16 단위입니다.

오류는 입력400 INVALID_REQUEST, 미완성400 SCRIPTURE_COPY_INCOMPLETE, 본문404 BIBLE_NOT_FOUND, 기록404 SCRIPTURE_COPY_NOT_FOUND, 충돌409 SCRIPTURE_COPY_CONFLICT입니다. 타인 기록은 없는 기록과 같은404이며, 이미 삭제된 UUID의 재삭제는204입니다.

## 데이터와 배포 순서

V42.3은 현행 main V42.2 다음으로 적용하며, 열린 결제 PR의 V43/V44를 앞서 적용할 필요가 없습니다. 병합/배포 직전에 기본 브랜치, 열린 관련 PR과 실제 DB 적용 상태를 다시 확인합니다. 기존 migration을 수정하거나 out-of-order를 활성화하지 않습니다.

모든 쓰기는 트랜잭션 안에서 활성 사용자 행을 잠근 뒤 필사 기록을 확인합니다. client UUID PK와 영속 revision으로 재시도와 동시 수정에 대응합니다. 탈퇴도 같은 사용자 잠금 순서를 사용하며 기록과 묵상을 물리 삭제합니다.

서버 API와 DB가 준비된 뒤 앱 기능을 공개합니다. 서버 main 병합은 자동 배포에 연결되므로 구현 요청만으로 병합/운영 배포하지 않습니다. 신규 환경변수·외부 라이브러리·AI 호출·읽기 완독/보상 지급은 없습니다.
