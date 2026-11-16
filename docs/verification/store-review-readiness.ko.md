# App Store·Google Play 심사 접근 운영 가이드

## 심사 전용 계정

- 저장소에 계정과 비밀번호를 커밋하지 않습니다. App Store Connect의 App Review Information과 Play Console의 앱 액세스 안내에만 입력합니다.
- 이메일 인증이 끝난 전용 일반 사용자 계정을 만들고 ACCOUNT·AI 필수 정책에 미리 동의합니다.
- 심사 기간에는 OTP, MFA, 지역 제한, 짧은 비밀번호 만료를 적용하지 않습니다.
- 무료 AI 잔여 횟수를 확보하고, 백엔드·OpenAI·성경 RAG 데이터가 심사 기간 내내 운영 상태인지 확인합니다.
- 심사 종료 뒤 비밀번호와 활성 세션을 회전합니다.

## QT·QR 접근 자료

- 심사 제출 직전에 종료일이 충분히 남은 QT 세션을 만들고 6자리 참여 코드와 QR 이미지를 준비합니다.
- QR만 주지 말고 동일한 6자리 코드를 텍스트로도 심사 메모에 제공합니다.
- 실제 물리 카메라가 없어도 확인할 수 있도록 `https://app.mypasture.app/q/qt/{code}` 링크와 화면 이동 순서를 함께 적습니다.
- 심사 계정이 이미 같은 세션에 참여해 재진입이 막히지 않는지 확인합니다.

## 소셜 로그인 릴리스 게이트

- 공개 `GET /api/v1/auth/social-login/providers`에서 출시 대상 제공자가 모두 `true`인지 확인합니다.
- iOS Bundle ID와 Android package는 `com.mypasture.app`으로 일치시킵니다.
- Google: Web/iOS/Android Client ID, Android Play App Signing SHA-1·SHA-256, 서버 audience 목록을 함께 확인합니다.
- Apple: App ID의 Sign in with Apple capability와 서버 accepted client ID에 Bundle ID를 반영합니다.
- Kakao: Native App Key, iOS Bundle ID, Android package/key hash, 서버 Kakao App ID를 확인합니다.
- Firebase의 `GoogleService-Info.plist`와 `google-services.json`이 같은 운영 앱 식별자를 가리키는지 확인합니다.
- Expo Go나 로컬 웹 성공으로 완료 처리하지 않고, TestFlight와 Play 내부 테스트의 스토어 서명 빌드에서 각각 로그인합니다.
- 버튼은 로컬 설정과 서버 준비 상태가 모두 유효할 때만 노출하며 Apple 버튼은 iOS에서만 표시합니다.

## 수동 스모크 순서

1. 깨끗한 설치에서 로그인 없이 성경을 엽니다.
2. 심사 계정으로 이메일 로그인 후 내정보·회원탈퇴 화면을 엽니다.
3. Google, Apple(iOS), Kakao 로그인을 각 플랫폼에서 별도 계정으로 검증합니다.
4. AI 목자 질문을 보내 근거 구절·기도문·신고 버튼을 확인합니다.
5. QT 코드 또는 QR로 참여하고 답변 작성·신고·사용자 차단·차단 해제를 확인합니다.
6. 오늘의 말씀과 알림 설정을 엽니다. 푸시 권한을 거부해도 앱이 정상 동작해야 합니다.
7. 외부 삭제 URL과 `privacy@mypasture.app` 메일 수신을 확인합니다.

Apple은 계정 기반 기능에 활성 데모 계정과 필요한 QR 같은 리소스를 제공하라고 명시합니다: https://developer.apple.com/app-store/review/guidelines/

## 현재 출시 차단 조건

- 전용 계정 또는 QR 코드가 만료됨
- 세 소셜 제공자 중 노출된 버튼 하나라도 실제 스토어 빌드에서 실패함
- 외부 계정 삭제 URL이 배포되지 않음
- 신고 대기열을 확인할 운영 담당자와 연락처가 없음
- Data Safety/App Privacy 답변이 `data-safety-inventory.ko.md`와 다름
