# 서버 환경 설정

서버 환경은 `local`과 `prod` 두 가지 Spring Profile로 관리합니다.

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
```

- Google은 모바일 앱에서 발급받은 토큰의 `aud`와 일치하도록 Web, iOS, Android Client ID를 각각 등록합니다.
- Apple은 앱의 Bundle ID 또는 Services ID 중 실제 로그인 토큰의 `aud`로 사용되는 값을 등록합니다.
- Kakao는 기본 사용자 정보 API 주소를 사용하며, 별도 프록시를 쓰는 경우에만 변경합니다.
- Client ID는 공개 식별자이지만 실제 ID 토큰, 액세스 토큰, Client Secret은 문서나 Git에 커밋하지 않습니다.

### 2. Gradle로 직접 실행

```bash
./gradlew bootRun
```

`local`이 기본 Profile이며 Spring이 `.env.local`을 가져옵니다. 직접 실행할 때는 기본적으로 H2를 사용합니다.

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
