# 도전과제

## 사용자 흐름

로그인 후 나의 목장에서 최근 업적과 대표 배지를 확인하고 전체 도전과제로 이동합니다. 목록은 전체/진행 중/획득 및 6계열 필터, 서버가 계산한 진행률, 실제 배지 수여일을 표시합니다. 획득한 배지 중 최대 3개를 선택한 순서대로 대표 배지로 저장하거나 모두 해제할 수 있습니다. 보상은 배지이며 별도 화폐/유료 기능을 지급하지 않습니다.

## 6계열 / 12개

- FIRST_CHAPTER: 정규 성경 장 최초 완독.
- READ_DAYS_7/30/100/365: 한 장 이상 완독한 서로 다른 날짜 수.
- READ_STREAK_7: 역대 최장 연속 완독 날짜가 7일 이상.
- FIRST_BOOK: 정규 책의 1..chapterCount 장을 모두 완독. 번역본별 같은 장은 중복 제외.
- BOOKMARKS_5/20: 현재 보유한 서로 다른 성경 위치 수. 삭제/재저장 횟수는 누적하지 않음.
- NOTE_DAYS_3/10/30: 한국 시간으로 메모를 최초 작성한 서로 다른 날짜 수. 편집일·같은 날 메모 수·메모에 연결된 절 수는 추가하지 않음.

읽기 날짜는 기존 read_on을 그대로 사용합니다. 기존 앱은 Asia/Seoul로 기록하지만 다른 클라이언트가 기록한 과거 날짜를 추정 변환하지 않습니다. 최초 수여 시각은 실제 반영 시각이며 과거 활동 시각으로 꾸미지 않습니다.

이미 획득한 배지는 원본 삭제나 연속 기록 중단으로 회수하지 않습니다. 메모 작성일 원장은 내용 없이 날짜만 보존합니다. 도입 전 이미 삭제된 기록은 복원할 수 없습니다.

## 서버 API / 데이터

- GET /api/v1/achievements: 본인 결과 읽기 전용 조회.
- POST /api/v1/achievements/reconcile: 남아 있는 과거 기록 소급, 반복 실행 멱등.
- PUT /api/v1/achievements/featured: 본인이 획득한 서로 다른 codes 최대 3개. 빈 목록은 해제.

JWT 사용자만 처리하며 요청의 userId나 진행 수치를 받지 않습니다. 표준 요청은 http/achievements.http를 참조합니다.

업적은 user_achievement_awards의 사용자+코드 unique로 영속 중복 방지를 보장합니다. user_note_activity_days는 사용자+날짜 unique, user_achievement_featured는 사용자+코드/사용자+순서 unique와 소유 수여 원장 FK를 가집니다. 사용자 행 잠금→원본 저장→JPA flush→서버 집계→업적 수여를 같은 트랜잭션에서 수행합니다. 삭제도 같은 사용자 락 아래 기존 기록을 먼저 평가합니다. 소프트 탈퇴 시 세 테이블도 명시적으로 지웁니다. V42.7의 사용자 탈퇴 전환 트리거가 이전 서버 버전의 삭제와 롤백에서도 같은 트랜잭션 안에서 세 테이블을 지우므로 파생 기록이 남지 않습니다.

앱의 읽기/북마크/메모 변경 후 업적 캐시를 무효화합니다. 초점 진입 reconcile와 대표 배지 PUT은 계정별 캐시를 공유하며 저장 중 조회가 예전 대표 배지를 덮지 않도록 차단합니다. API 후 계정과 로그인 세대가 바뀌면 이전 응답을 폐기합니다.

## 검증 및 배포 순서

신규 라이브러리/환경변수는 없습니다. V42.7 migration은 최신 main V42.6 다음의 번호이며 열린 결제 PR의 V43/V44보다 먼저 적용됩니다. 실제 열린 PR/기본 브랜치/운영 적용 순서를 병합 전에 재확인하고 필요한 경우 새 파일 번호를 조정해야 합니다. 기존 적용된 migration은 수정하지 않습니다.

서버 migration/API가 준비된 후 클라이언트를 배포해야 합니다. 구현 요청만으로 운영 배포나 스토어 출시를 진행하지 않습니다.

```sh
./gradlew test --tests '*AchievementCatalogTests' --tests '*AchievementControllerTests' --no-daemon
./gradlew test --no-daemon
```

실제 PostgreSQL 검증은 ACHIEVEMENT_TEST_JDBC_URL, ACHIEVEMENT_TEST_JDBC_USER, ACHIEVEMENT_TEST_JDBC_PASSWORD를 일회성 테스트 DB에 지정합니다. CI의 PostgreSQL job에 Flyway/업적 통합 테스트를 포함해야 합니다. 운영 DB로 테스트하지 않습니다.

실제 검사 결과는 docs/tasks/167-reading-growth.md와 해당 QA/Review 기록에서 관리합니다. 로컬과 CI의 전체 테스트, PostgreSQL/Flyway 검증을 완료 조건으로 유지합니다.
