# My Pasture Service Flow

이 문서는 현재 구현된 My Pasture 백엔드 API를 기준으로 사용자가 앱에 들어와 말씀을 읽고, 신앙 성향을 확인하고, 큐티목장 오프라인 모임에 참여하는 흐름을 정리합니다.

## 전체 흐름

```mermaid
flowchart TD
    A[앱 실행] --> B{로그인 여부}
    B -- 미로그인 --> C[이메일 회원가입 또는 로그인]
    B -- 로그인됨 --> D[홈]
    C --> D
    D --> E[성경 읽기]
    E --> F[장 전체 조회]
    E --> G[단어 검색]
    D --> H[신앙 성향 테스트]
    H --> I[테스트 정의 조회]
    I --> J[Assessment 시작]
    J --> K[문항별 1~5점 응답]
    K --> L[완료 및 유형 결과 생성]
    L --> M[유형별 추천 루틴 확인]
    D --> N[큐티목장]
    N --> O[큐티 본문 추천]
    O --> P[큐티목장 생성]
    P --> Q[오프라인 큐티 세션 생성]
    Q --> R[QR Payload 및 Offline Code 발급]
    R --> S[QR 스캔]
    S --> T[오프라인 큐티 세션 조회]
```

## 인증 흐름

```mermaid
sequenceDiagram
    participant App as Mobile App
    participant API as Spring Boot API
    participant DB as PostgreSQL

    App->>API: POST /api/v1/auth/signup
    API->>DB: users, auth_accounts 저장
    DB-->>API: 저장 완료
    API-->>App: user profile
    App->>API: POST /api/v1/auth/login
    API->>DB: auth_accounts 조회
    API-->>App: dev token + user profile
```

## 성경 읽기 흐름

```mermaid
flowchart LR
    A[번역본 조회] --> B[권 목록 조회]
    B --> C[권/장 선택]
    C --> D[장 전체 구절 조회]
    B --> E[단어 검색]
    E --> F[검색 결과 구절 목록]
    F --> C
```

서버 시작 시 import가 활성화되어 있으면 `data/kor_html` 원본을 검증해 PostgreSQL에 멱등 적재합니다.
읽기와 검색 API는 DB만 조회하며, 원본 파일이 없는 장도 `EMPTY` 상태로 구분해 정상 응답합니다.

## 신앙 성향 테스트 흐름

```mermaid
stateDiagram-v2
    [*] --> CurrentTest: 테스트 정의 조회
    CurrentTest --> InProgress: assessment 시작
    InProgress --> InProgress: 문항별 답변 제출
    InProgress --> Completed: 모든 문항 답변 후 완료
    Completed --> Result: 유형/축별 점수/추천 생성
    Result --> [*]
```

점수 계산은 4개 축의 좌우 점수를 합산해 높은 쪽 코드를 선택합니다.

- WORD_PRAYER: W 또는 P
- MEDITATION_ACTION: M 또는 A
- COMMUNITY_SOLITUDE: C 또는 S
- GRACE_DISCIPLINE: G 또는 D

예: `WMCG` = 조용한 말씀 순례자

## 큐티목장 및 QR 흐름

```mermaid
sequenceDiagram
    participant Leader as 목장 리더
    participant API as Spring Boot API
    participant DB as PostgreSQL
    participant Member as 목장원

    Leader->>API: GET /api/v1/quiet-time/passages/recommendations
    API->>DB: 추천 기준 점수 기반 본문 조회
    API-->>Leader: 추천 본문 목록
    Leader->>API: POST /api/v1/quiet-time/pastures
    API->>DB: 큐티목장 생성
    API-->>Leader: pastureId, inviteCode
    Leader->>API: POST /api/v1/quiet-time/pastures/{pastureId}/sessions
    API->>DB: offlineCode, inviteUrl, expiresAt 저장
    API-->>Leader: HTTPS inviteUrl
    Leader->>Leader: 프론트에서 inviteUrl을 QR로 렌더링
    Member->>API: GET /api/v1/quiet-time/offline/{offlineCode}
    API->>DB: 오프라인 세션 조회
    API-->>Member: 본문, 질문, 실천, 기도 가이드
    Member->>API: POST /api/v1/quiet-time/offline/{offlineCode}/join
    API->>DB: 참여자 등록
    Member->>API: GET /sessions/{sessionId}/answers
    API-->>Member: 질문별 최신 답변 스냅샷
    Member->>API: GET /sessions/{sessionId}/answers/stream
    Member->>API: PUT /sessions/{sessionId}/answers/{category}/{index}
    API->>DB: 답변 생성 또는 갱신
    API-->>Member: answer-updated SSE
    API-->>Leader: answer-updated SSE
```

실시간 채널은 빠른 화면 반영을 담당하고 정합성의 원본은 PostgreSQL입니다. 연결이 끊겼다가 복구되면 클라이언트는 답변 스냅샷을 다시 조회합니다. 운영의 다중 인스턴스에서는 Redis Pub/Sub이 각 인스턴스의 SSE 연결로 변경 이벤트를 전달합니다.

QR에는 앱 전용 scheme만 넣지 않고, 운영에서는 HTTPS landing URL을 넣는 것을 권장합니다.

```text
https://mypasture.app/q/qt/{offlineCode}
```

앱에는 Universal Link/App Link 라우팅이 구성되어 있습니다. 운영 전 mypasture.app에 iOS AASA, Android assetlinks.json, 앱 미설치 사용자를 위한 설치 안내 페이지를 배포해야 합니다.

## API 테스트 순서

아래 순서대로 [mypasture-flow.http](../http/mypasture-flow.http)를 `local` 환경으로 실행하면 전체 서비스 흐름을 확인할 수 있습니다. 변수는 [http-client.env.json](../http/http-client.env.json)에 분리되어 있습니다.

IntelliJ HTTP Client에서 요청 실행 시 `Run with environment`를 `local`로 선택하세요.

1. Health check
2. 회원가입
3. 로그인 및 token 저장
4. 성경 번역본/권 목록 조회
5. 성경 장 전체 조회
6. 성경 단어 검색
7. 신앙 성향 테스트 정의 조회 및 question id 저장
8. Assessment 시작
9. 8개 문항 답변 제출
10. Assessment 완료 및 result id 저장
11. 결과 조회
12. 큐티 본문 추천 및 passage id 저장
13. 큐티목장 생성 및 pasture id 저장
14. 오프라인 큐티 세션 생성 및 offlineCode 저장
15. QR scan 이후 오프라인 세션 조회
