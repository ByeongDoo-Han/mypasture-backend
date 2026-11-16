# App Privacy·Google Play Data Safety 실데이터 대조표

이 문서는 2026-10-02 코드 기준 기술 인벤토리입니다. 스토어 답변을 대신하는 법률 문서가 아니며, 매 릴리스에서 네이티브 SDK 버전·서버 로그·공급자 계약을 다시 확인해야 합니다.

## 앱과 서버 데이터 흐름

| 데이터 | 수집·저장 위치 | 외부 처리 | 목적 | 사용자 연결 | 삭제 |
|---|---|---|---|---|---|
| 이메일, 표시 이름 | PostgreSQL `users`, `auth_accounts` | 선택한 Google·Apple·Kakao 인증 | 계정·인증 | 연결됨 | 앱 탈퇴 즉시 삭제·익명화 |
| 소셜 제공자 사용자 ID | PostgreSQL `auth_accounts` | 해당 로그인 제공자 | 계정 연결 | 연결됨 | 탈퇴 시 삭제 |
| 비밀번호 | 원문 미저장, BCrypt 해시만 저장 | 없음 | 이메일 로그인 | 연결됨 | 탈퇴 시 삭제 |
| AI 질문·응답·근거 | 서버 암호화 대화 문맥·답변 기록·일시적 요청 영수증 | OpenAI Responses/Embeddings API | AI 목자와 RAG | 연결됨 | 기록은 생성일 1년 또는 이용자 삭제·탈퇴 시 삭제, 문맥은 마지막 사용일 30일 |
| 종교적 신념·감정·기도 내용 | 사용자가 AI/QT 자유 입력 시 포함 가능 | AI 동의 후 필요한 질문과 근거만 OpenAI 전송 | 묵상·상담 | 연결됨 | 탈퇴 시 개인 기록 삭제, 공유 QT는 익명화 |
| QT 답변·모임 참여 | PostgreSQL | 없음 | 공동체 기능 | 연결됨 | 탈퇴 시 사용자·기기 연결 제거 후 익명화 |
| 성경 읽기·북마크·메모 | PostgreSQL, 일부 로컬 캐시 | 없음 | 개인화·오프라인 읽기 | 연결됨 | 탈퇴 시 서버 데이터 삭제 |
| 푸시 토큰, 앱 설치 ID | PostgreSQL 및 Firebase Cloud Messaging | Google Firebase | 알림 전달·중복 방지 | 계정·기기 연결 | 로그아웃/탈퇴 시 비활성화·삭제 |
| 알림 전달·열람 상태 | PostgreSQL | Firebase 전달 결과 | 알림함·장애 대응 | 연결됨 | 탈퇴 시 삭제 |
| 카메라 영상 | 기기에서 QR 프레임 처리 | 없음 | QT 참여 QR 스캔 | 저장·업로드 안 함 | 앱 종료 시 메모리 해제 |
| IP, User-Agent, 요청 ID, 오류 | 서버·프록시 로그 | GCP 운영 인프라 | 보안·장애 대응 | 간접 연결 가능 | 운영 보유기간 후 삭제 |
| 신고 상세·대상 스냅샷 | PostgreSQL AES-GCM 암호문 | 없음 | 안전·분쟁 대응 | 신고자/대상과 연결 | 탈퇴·운영 보존 정책에 따라 삭제·연결 해제 |
| 꾸미기 보상 등록 | PostgreSQL | 없음 | 무료 이벤트 보상 | 연결됨 | 탈퇴 시 삭제 |

## 포함된 SDK와 선언 확인

- Firebase Messaging은 메시지 전달을 위해 Firebase Installation ID를 사용합니다. Firebase 문서의 보유·삭제 동작을 Data Safety와 개인정보처리방침에 반영합니다: https://firebase.google.com/support/privacy
- Google 로그인은 사용자가 누른 경우에만 실행하며 ID Token을 서버 검증에 사용합니다. 광고 ID나 연락처 권한을 요청하지 않습니다.
- Kakao는 현재 `profile_nickname`과 서비스 사용자 ID만 요청하고 `account_email` scope는 요청하지 않습니다. 콘솔 동의항목과 코드가 달라지면 즉시 이 표를 갱신합니다: https://developers.kakao.com/docs/en/kakaologin/utilize
- Apple 로그인은 이름·이메일 범위만 요청하며, Apple이 이메일 가리기를 허용합니다.
- OpenAI API에는 `store=false`를 사용합니다. 기본 API 데이터가 모델 학습에 쓰이지 않더라도 abuse monitoring 로그 보유 정책은 별도로 확인해야 합니다: https://platform.openai.com/docs/models/default-usage-policies-by-endpoint
- 현재 Analytics, Crashlytics, 광고 SDK, ATT/IDFA 수집 코드는 포함하지 않습니다. 향후 SDK를 추가하면 “분석 안 함/추적 안 함” 선언을 그대로 유지하면 안 됩니다.

## 스토어 입력 전 체크

1. `package.json`, `app.config.js`, 생성된 iOS `PrivacyInfo.xcprivacy`, Android merged manifest를 확인합니다.
2. App Store App Privacy에서 최소한 Contact Info, User ID, Device ID, Other User Content, Sensitive Info, Product Interaction/Diagnostics 해당 여부를 실제 보유·전송과 대조합니다.
3. Google Play Data Safety에서 수집·공유·암호화·삭제 가능 여부를 공급자 계약과 Play의 “서비스 제공자” 정의에 따라 답합니다.
4. `https://mypasture.app/account-deletion`이 로그인 없이 열리고 실제 요청 채널이 작동하는지 확인합니다.
5. Firebase·OpenAI·Google·Apple·Kakao 설정 또는 SDK 버전이 바뀐 PR에는 이 문서 검토를 필수 체크로 둡니다.

Apple 데이터 유형 기준: https://developer.apple.com/app-store/app-privacy-details/

## 콘솔 입력 작업표

아래 표는 현재 코드에서 수집 사실이 확인된 항목입니다. “공유” 여부는 각 공급자가 계약상 서비스 제공자로만 처리하는지 확인한 뒤 최종 선택합니다. 수집 사실을 숨기기 위해 공급자 처리를 `수집 안 함`으로 표시해서는 안 됩니다.

| 실제 흐름 | App Store App Privacy 후보 | Google Play Data Safety 후보 | 추적 |
|---|---|---|---|
| 이메일·표시 이름 | Contact Info: Email Address, Name | Personal info: Email address, Name | 사용 안 함 |
| 내부 사용자 ID·소셜 제공자 ID | Identifiers: User ID | User IDs | 사용 안 함 |
| FCM 토큰·Firebase 설치 식별자 | Identifiers: Device ID | Device or other IDs | 사용 안 함 |
| AI 질문·기도·QT 답변 | User Content: Other User Content, Sensitive Info | Other user-generated content, Religious or philosophical beliefs | 사용 안 함 |
| 읽기·북마크·알림 상호작용 | Usage Data: Product Interaction | App interactions | 사용 안 함 |
| 오류·보안 요청 로그 | Diagnostics 또는 Other Data | Diagnostics, App info and performance | 사용 안 함 |

현재 코드에는 광고 SDK, IDFA/ATT, 연락처, 위치, 사진 라이브러리 업로드가 없습니다. QR 카메라 프레임은 서버로 보내거나 파일로 저장하지 않으므로 카메라 권한 자체와 서버 데이터 수집을 구분해 답합니다.

릴리스 담당자는 콘솔 입력값을 캡처해 PR 또는 배포 기록에 첨부하고, 다음 네 항목을 서명합니다.

1. 전송 중 TLS 암호화
2. 앱 내 삭제와 공개 삭제 URL
3. 공급자별 처리 목적과 공유 예외 근거
4. SDK 추가·삭제 이후 이 표와 콘솔의 동시 갱신
