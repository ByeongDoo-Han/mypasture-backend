# 서버 환경 설정

GCP Terraform 기반 staging/production 구축은 [GCP Terraform 배포 가이드](gcp-terraform-deployment.ko.md)를 참고하세요.

서버 환경은 `local`과 `prod` 두 가지 Spring Profile로 관리합니다.

오늘의 말씀은 기본 `DAILY_WORD_ROTATION_ENABLED=true`로 요일별 목록을 자동 제공합니다. `DAILY_WORD_GENERATION_ENABLED=true`인 전용 worker가 오늘·내일을 준비하고 API 인스턴스는 조회 시 현재 날짜 게시본을 복구할 수 있습니다. `DAILY_WORD_ROTATION_ENABLED=false`일 때만 기존 provider/AI 초안 생성과 관리자 검수 경로를 사용합니다. 운영은 V42.8을 먼저 적용해야 하며 본문 목록·범위·실패 처리 기준은 [오늘의 말씀 운영 가이드](daily-word-publishing-operations.ko.md)를 확인하세요.

6자리 현장 QT 참여 코드는 로그인 사용자만 해석할 수 있으며, 운영에서는 Redis rate limit을 활성화합니다.

```dotenv
RATE_LIMIT_ENABLED=true
RATE_LIMIT_FAIL_CLOSED=true
RATE_LIMIT_TRUST_FORWARDED_HEADERS=true
QUIET_TIME_JOIN_CODE_RATE_LIMIT_PER_WINDOW=12
```

`QUIET_TIME_JOIN_CODE_RATE_LIMIT_PER_WINDOW`은 `RATE_LIMIT_WINDOW_SECONDS` 동안 인증 사용자 한 명이 시도할 수 있는 코드 해석 횟수입니다. Nginx 또는 로드 밸런서 뒤에서 운영할 때만 신뢰 가능한 프록시 구성과 함께 `RATE_LIMIT_TRUST_FORWARDED_HEADERS=true`를 사용합니다.

## Local

### 1. 환경 변수 준비

```bash
cp .env.local.example .env.local
```

`.env.local`에 로컬 DB, JWT, 메일, AI 설정을 입력합니다. 소셜 로그인을 테스트하려면 사용하는 플랫폼의 OAuth Client ID도 설정해야 합니다.

```dotenv
GOOGLE_OAUTH_WEB_CLIENT_IDS=
GOOGLE_OAUTH_IOS_CLIENT_IDS=
GOOGLE_OAUTH_ANDROID_CLIENT_IDS=
APPLE_OAUTH_CLIENT_IDS=
KAKAO_USER_INFO_URI=https://kapi.kakao.com/v2/user/me
KAKAO_WEB_REST_API_KEY=
KAKAO_WEB_CLIENT_SECRET=
KAKAO_WEB_REDIRECT_URI=https://app.mypasture.app/oauth/kakao
```

- Google은 모바일 앱에서 발급받은 토큰의 `aud`와 일치하도록 Web, iOS, Android Client ID를 각각 등록합니다.
- Apple은 앱의 Bundle ID 또는 Services ID 중 실제 로그인 토큰의 `aud`로 사용되는 값을 등록합니다.
- Kakao는 기본 사용자 정보 API 주소를 사용하며, 별도 프록시를 쓰는 경우에만 변경합니다.
- Client ID는 공개 식별자이지만 실제 ID 토큰, 액세스 토큰, Client Secret은 문서나 Git에 커밋하지 않습니다.

### 이메일 회원가입 인증

로컬에서는 `EMAIL_VERIFICATION_DELIVERY=log`로 두고 서버 로그의 `DEV ONLY` 인증번호를 사용합니다.
운영에서는 `smtp`로 변경하고 HMAC 비밀값을 Secret Manager에서 주입해야 합니다.

```dotenv
EMAIL_VERIFICATION_REQUIRED=true
EMAIL_VERIFICATION_CODE_TTL_MINUTES=10
EMAIL_VERIFICATION_SIGNUP_TOKEN_TTL_MINUTES=30
EMAIL_VERIFICATION_REQUEST_COOLDOWN_SECONDS=60
EMAIL_VERIFICATION_MAX_ATTEMPTS=5
EMAIL_VERIFICATION_DELIVERY=log
EMAIL_VERIFICATION_FROM=no-reply@mypasture.app
EMAIL_VERIFICATION_HMAC_SECRET=32자_이상의_무작위_운영_비밀값
```

운영 SMTP는 기존 `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD` 설정을 함께 사용합니다. 인증번호와 SMTP 비밀번호를 Git, `.http` 공유 환경 파일,
운영 로그에 기록하지 않습니다.

### 2. Gradle로 직접 실행

```bash
./gradlew bootRun
```

`local`이 기본 Profile이며 Spring이 `.env.local`을 가져옵니다. 직접 실행할 때는 기본적으로 H2를 사용합니다.

### 1910 성경 DB 적재

로컬 Docker에서는 `BIBLE_IMPORT_ENABLED=true`가 기본값입니다. 서버 시작 시 `data/kor_html`을 검증하고
66권, 1,189장과 각 절을 DB에 멱등 적재합니다. 원본 파일이 없는 장은 `bible_chapters.content_status=EMPTY`로
저장하며 가짜 절 번호나 빈 절 행은 만들지 않습니다.

```dotenv
KOR1910_HTML_PATH=data/kor_html
BIBLE_IMPORT_ENABLED=true
```

반복 실행해도 `(version, book_id, chapter, verse)`가 같은 기존 절의 ID를 유지하므로 북마크와 하이라이트
참조가 끊기지 않습니다. 운영에서는 여러 서버가 동시에 원본을 읽지 않도록 배포 작업 한 인스턴스에만
`BIBLE_IMPORT_ENABLED=true`를 주입하세요. PostgreSQL advisory lock이 실수로 겹친 실행도 한 번만 허용합니다.

적재 결과는 시작 로그의 `KOR1910 Bible import complete` 항목에서 권, 장, 빈 장, 절 개수를 확인할 수 있습니다.

### AI 목자 pgvector index

성경 DB 적재 후 AI 목자의 의미 검색을 사용하려면 절 embedding을 한 번 생성해야 합니다. 운영 방법과
안전한 모델 전환 절차는 [AI 목자 pgvector RAG 운영 가이드](ai-rag-operations.ko.md)를 따릅니다.

```dotenv
AI_MOCK_ENABLED=false
AI_EMBEDDING_INDEXING_ENABLED=true
AI_EMBEDDING_BATCH_SIZE=100
AI_RAG_LEXICAL_FALLBACK_ENABLED=true
AI_STREAM_TIMEOUT_MS=60000
AI_STREAM_CORE_POOL_SIZE=8
AI_STREAM_MAX_POOL_SIZE=64
AI_STREAM_QUEUE_CAPACITY=200
AI_IDEMPOTENCY_RESPONSE_TTL_SECONDS=86400
AI_IDEMPOTENCY_RECEIPT_RETENTION_DAYS=30
AI_IDEMPOTENCY_PENDING_TIMEOUT_MS=120000
OPENAI_GENERATOR_MODEL=gpt-5.6-terra
OPENAI_EVALUATOR_MODEL=gpt-5.6-luna
OPENAI_REASONING_EFFORT=low
OPENAI_EVALUATOR_REASONING_EFFORT=none
AI_EVALUATION_ENABLED=true
AI_EVALUATION_THRESHOLD=4
AI_EVALUATION_MAX_OUTPUT_TOKENS=500
AI_MAX_REVISION_COUNT=1
```

백필 worker는 한 서버에서만 활성화하고, 관리자 상태 API에서 `remainingVerses=0`을 확인한 후 다시
비활성화합니다. lexical fallback은 embedding API 장애 시에도 본문 키워드 검색을 계속 제공하기 위한
운영 안전장치입니다. 로컬의 `AI_MOCK_ENABLED=true`는 결정적 가짜 embedding과 lexical fallback으로
흐름만 검증하며 실제 OpenAI 검색 품질을 의미하지 않습니다. AI 요청은 처리 중인 `PENDING`과 성공한
`COMPLETED` 기록만 일일 한도에 포함하며, 최종 실패한 `FAILED` 기록은 횟수에서 제외합니다.

생성·평가 모델의 환경 변수는 DB에 활성 버전이 없을 때만 기본값으로 사용됩니다. 운영 중 변경은 관리자
`/ai-models` 화면에서 새 버전으로 활성화하며, 과거 버전 복원도 새 이력으로 남습니다. 평가가 켜진 경우
응답은 서버에서 잠시 버퍼링되어 근거·인용·안전성·관련성·목회적 어조를 검사한 뒤 최종본만 전송됩니다.

### OpenAI API Key 최소 비용 검증

실제 AI 목자를 사용할 때만 `.env.local`에서 mock을 끕니다. API Key는 예제 파일이나 Git에 기록하지
않습니다.

```dotenv
OPENAI_API_KEY=발급받은_프로젝트_API_KEY
AI_MOCK_ENABLED=false
OPENAI_SMOKE_TEST_MODEL=gpt-4.1-nano
OPENAI_SMOKE_MAX_OUTPUT_TOKENS=16
```

변수명은 반드시 단수형 `OPENAI_API_KEY`를 사용합니다. `OPENAI_API_KEYS`는 애플리케이션이 읽지 않습니다.
셸에 이전 키를 `export`한 적이 있다면 `unset OPENAI_API_KEY` 후 서버를 실행해 `.env.local`과 충돌하지
않게 합니다.

다음 전용 작업은 `.env.local`을 읽어 운영 모델 접근 권한을 토큰 소비 없는 모델 조회로 확인한 뒤,
Responses API에 비추론형 `gpt-4.1-nano` 요청을 한 번만 보냅니다. 입력은 `Reply OK.`이며 출력 상한은
API가 허용하는 최소값인 16토큰입니다. 짧은 응답에 reasoning token을 쓰지 않아 연결 확인 비용을
최소화합니다.
응답 본문과 API Key는 출력하지 않고 실제 input/output/total token 수만 표시합니다.

```bash
./gradlew openAiSmokeTest --rerun-tasks
```

`./gradlew test`와 CI에서는 `openai-live` tag를 제외하므로 이 요청과 비용이 발생하지 않습니다. 이 smoke
test는 키, 결제 한도, 모델 접근, Responses API 연결을 확인하며 전체 RAG 품질 테스트를 대신하지 않습니다.

### 3. Docker Compose로 실행

```bash
docker compose --env-file .env.local up -d --build
```

백엔드만 다시 빌드하고 실행하려면 다음 명령을 사용합니다.

```bash
docker compose --env-file .env.local up -d --build backend
```

Docker Compose는 기본적으로 프로젝트 루트의 `.env`만 자동으로 읽습니다. 따라서 `.env.local`을 사용하는 개발 환경에서는 반드시 `--env-file .env.local`을 지정해야 합니다. 이 옵션 없이 실행하면 OAuth Client ID가 빈 값으로 컨테이너에 전달되어 소셜 로그인 검증이 실패할 수 있습니다.

`.env.local`을 수정한 뒤에는 기존 컨테이너를 재생성합니다.

```bash
docker compose --env-file .env.local up -d --force-recreate backend
```

서버 상태를 확인합니다.

```bash
docker compose --env-file .env.local ps
curl http://localhost:8080/actuator/health
```

정상 응답은 `status`가 `UP`입니다.

### 4. 소셜 로그인 테스트

`POST /api/v1/auth/social-login` 요청에는 공급자에 맞는 토큰을 전달합니다.

| 공급자 | 요청 필드 | 토큰 종류 |
| --- | --- | --- |
| Google | `idToken` | Google OpenID Connect ID Token |
| Apple | `idToken` | Sign in with Apple Identity Token |
| Kakao | `accessToken` | Kakao Login Access Token |

토큰은 로그인 직후 새로 발급받은 값을 사용합니다. Google 또는 Apple 액세스 토큰을 `idToken`에 넣거나, 만료된 토큰을 사용하면 검증에 실패합니다.

| 상태 코드와 오류 코드 | 의미 | 확인할 내용 |
| --- | --- | --- |
| `401 INVALID_SOCIAL_TOKEN` | 토큰 자체를 검증할 수 없음 | 토큰 만료 여부, 공급자, `idToken`/`accessToken` 필드, 토큰의 `aud` |
| `503 SOCIAL_PROVIDER_MISCONFIGURED` | 서버의 공급자 설정이 누락됨 | `.env.local`의 Client ID와 Compose의 `--env-file` 옵션 |

`401`은 Spring Security가 로그인 API를 차단했다는 뜻이 아니라, 소셜 공급자 토큰 검증 단계에서 실패했다는 뜻입니다.

## Production

운영 배포에서는 파일을 서버 이미지에 포함하지 않고 GCP Secret Manager와 런타임 환경변수를 사용합니다. 로컬에서 운영 설정 검증이 필요한 경우에만 다음처럼 준비합니다.

```bash
cp .env.prod.example .env.prod
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

`.env.local`과 `.env.prod`는 Git에서 제외됩니다. `JWT_SECRET`, DB 비밀번호, SMTP 비밀번호, OpenAI 키 같은 비밀값은 예제 파일에 기록하지 않습니다.

## AI 목자 개인 기록

AI 기록 기능은 새 비밀 환경변수를 추가하지 않고 기존 `AI_PRIVACY_HASH_SECRET`에서 history 전용 AES-GCM 키와 cursor 서명 키를 용도별로 유도합니다. 이 값을 바로 교체하면 기존 기록을 복호화할 수 없으므로 운영 키 회전은 이전 키로 재암호화하는 절차·백업 복구 검증을 먼저 준비해야 합니다. 비밀값을 작업 기록이나 테스트 로그에 넣지 않습니다.

`AI_PASTOR_VERSION`은 프롬프트·검색·답변 정책의 생성 파이프라인 버전이며 모델 설정 버전과 별개입니다. 해당 정책을 바꿀 때 이 상수도 갱신합니다. 응답 캐시 범위에도 포함하므로 이전 파이프라인의 캐시를 새 버전의 기록으로 재사용하지 않습니다.

원본 질문 생성 후 365일이 지나면 기록 조회에서 제외되고 worker 정기 파기가 연결된 개선본을 함께 삭제합니다. worker 실행·실패와 만료 행 잔류를 운영 점검에 포함합니다. 직접 삭제·탈퇴는 별도의 즉시 처리 경로입니다.

검증 전용 `AI_HISTORY_TEST_JDBC_URL`, `AI_HISTORY_TEST_JDBC_USER`, `AI_HISTORY_TEST_JDBC_PASSWORD`는 테스트 클래스가 일회용 PostgreSQL에 Flyway·JPA validate 검사를 수행하는 입력입니다. 사용자·기록을 정리하는 fixture가 있으므로 운영 DB를 지정하지 않습니다. 운영 애플리케이션 환경변수로 추가하지 않습니다.
