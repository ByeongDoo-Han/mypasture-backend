# 서버 환경 설정

서버 환경은 `local`과 `prod` 두 가지 Spring Profile로 관리합니다.

## Local

```bash
cp .env.local.example .env.local
./gradlew bootRun
```

`local`이 기본 Profile이며 Spring이 `.env.local`을 자동으로 가져옵니다. 직접 실행할 때는 H2를 사용하고, Docker Compose는 같은 파일에서 PostgreSQL 컨테이너 설정을 가져옵니다.

```bash
docker compose --env-file .env.local up --build
```

## Production

운영 배포에서는 파일을 서버 이미지에 포함하지 않고 GCP Secret Manager와 런타임 환경변수를 사용합니다. 로컬에서 운영 설정 검증이 필요한 경우에만 다음처럼 준비합니다.

```bash
cp .env.prod.example .env.prod
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

`.env.local`과 `.env.prod`는 Git에서 제외됩니다. `JWT_SECRET`, DB 비밀번호, SMTP 비밀번호, OpenAI 키 같은 비밀값은 예제 파일에 기록하지 않습니다.
