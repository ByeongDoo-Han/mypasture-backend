# 일별 활동 잔디 API

로그인 상태로 앱/웹을 이용한 날과 기존 성경 읽기 기록을 한국 시간 기준으로 보여줍니다.

- `PUT /api/v1/activity/today`: JWT 사용자와 서버 날짜로 오늘 방문 기록. 본문은 없고 `{ date }`를 반환합니다.
- `GET /api/v1/activity?year=2026`: 본인 기록만 조회. 연도를 생략하면 한국 시간의 올해입니다. 2000년~현재 연도만 허용하고 잘못된 값은 400입니다.
- 응답: `{ year, timezone, today, days: [{ date, visited, chaptersRead, chaptersCompleted }] }`. 해당 연도 시작부터 오늘/연말까지 활동 없는 날도 포함하고 미래는 제외합니다.
- 기존 날짜별 읽기 기록을 장 단위로 집계합니다. 같은 날 같은 장은 번역본에 관계없이 한 번 세며, 하나의 기록이라도 완독이면 완독 장 수에 포함합니다.
- 방문은 사용자 행의 DB 잠금과 `(user_id, visited_on)` unique constraint로 동시 호출에도 한 행만 생성됩니다. 클라이언트는 전경/자정/로그인 복원 시 호출하며 보상이나 QT 출석을 만들지 않습니다.
- 과거 방문은 추정하지 않습니다. 기존 성경 읽기 기록은 그대로 조회됩니다.
- 탈퇴 시 `JdbcAccountDataErasureAdapter`가 방문 기록을 함께 삭제합니다.

## 배포와 검증

새 환경변수는 없습니다. 마이그레이션 `V42.1__user_visits.sql`은 열려 있는 결제 PR #135의 V43/V44와 충돌하지 않게 V42.1을 사용합니다. 서버 API 및 마이그레이션을 먼저 배포한 뒤 앱 Issue #105를 배포합니다.

활동·읽기 변경은 담당·직접 인접 회귀를 선택합니다. 공통 스키마·공유 설정 등 전역 영향일 때만 `./gradlew test` 전체를 실행합니다. 실제 PostgreSQL/Flyway 검사:

```sh
ACTIVITY_TEST_JDBC_URL=jdbc:postgresql://localhost:55432/activity_test \
ACTIVITY_TEST_JDBC_USER=activity_test \
ACTIVITY_TEST_JDBC_PASSWORD=<test-password> \
./gradlew test --tests '*ActivityCalendarControllerTests' --tests '*ReadingActivityIntegrationTests' --rerun-tasks
```

CI의 PostgreSQL 서비스에서도 같은 인증/집계/중복 요청 테스트와 Flyway 검증을 실행합니다.

## 읽기 API 통합 검증

읽기 진행률 PUT부터 활동 GET까지 실제 HTTP 계약으로 검증합니다. 같은 사용자의 여러 기기가 읽기를 저장할 때 사용자 행 잠금으로 최초 행 생성과 완독 상태 갱신을 직렬화합니다. 장 수는 하루 한 번 집계하고 완독 뒤 도착한 미완독 요청이 완독 상태를 되돌리지 않습니다.
