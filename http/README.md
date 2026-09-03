# HTTP API 테스트

IntelliJ HTTP Client에서 실행 환경을 `local`로 선택한 뒤 각 요청 왼쪽의 실행 버튼을 사용합니다.

## 자동 검증

`social-login-validation.http`는 실제 OAuth 토큰 없이 실행할 수 있습니다. 지원하지 않는 제공자와 제공자별 필수 토큰 누락 응답을 검증합니다.

## 실제 제공자 연동

```bash
cp http/http-client.private.env.json.example http/http-client.private.env.json
```

모바일 로그인 과정에서 받은 짧은 수명의 Google/Apple ID Token과 Kakao Access Token을 private 파일에 입력한 뒤 `social-login-live.http`를 실행합니다. 이 파일은 Git에서 제외되며 Client Secret은 입력하지 않습니다.

Google/Apple 토큰의 `aud`는 서버 `.env.local`에 등록한 Client ID 중 하나와 일치해야 합니다. 실제 로그인 요청 다음의 `/me` 요청까지 실행하면 My Pasture JWT 발급과 인증을 함께 확인할 수 있습니다.

`store-review-smoke.http`는 심사 전용 계정으로 AI 권한, 활성 QT 참여 코드, QR 도착 화면을 한 번에 확인합니다. 심사 계정 비밀번호와 참여 코드는 반드시 `http-client.private.env.json`에만 보관하고, 배포 직전과 심사 제출 직전에 각각 실행합니다.

`moderation.http`는 본인이 받은 AI 답변과 참여 가능한 QT 답변에 대해서만 신고가 성공합니다. 테스트용 대상 ID 역시 private 환경 파일에서 관리합니다.

## QT 실시간 답변

`quiet-time-live-answers.http`를 단독 실행할 때는 로그인 응답의 My Pasture `accessToken`과 생성된 `quietTimeSessionId`를 `http-client.private.env.json`에 입력합니다. 스트림 요청을 먼저 열어 둔 뒤 답변 PUT 요청을 실행하면 `answer-updated` 이벤트를 확인할 수 있습니다.

## 알림센터

`notifications.http`를 실행하기 전에 private 환경 파일의 `accessToken`과 `adminAccessToken`을 실제 My Pasture JWT로 설정합니다. 오늘의 말씀 게시 후 목록 조회를 먼저 실행하면 첫 항목 ID가 `notificationId` 전역 변수에 저장되어 이어지는 읽음 요청에 사용됩니다. 목록이 비어 있으면 게시된 오늘의 말씀을 먼저 준비해야 합니다.
