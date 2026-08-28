# C4 Level 1: System Context

My Pasture 시스템의 사용자와 외부 시스템 경계를 표현합니다. 모바일과 관리자는 별도 배포 단위이지만 하나의 서비스 경험을 구성합니다.

```mermaid
flowchart LR
    Member["일반 성도\n성경 읽기, AI 목자, QT"]
    Leader["목장 리더\nQT 모임 운영"]
    Operator["운영 관리자\n콘텐츠·AI·전달 운영"]

    System["My Pasture\nAI 기반 성경·QT 서비스"]

    OAuth["Google / Apple / Kakao\n사용자 신원 확인"]
    OpenAI["OpenAI API\n답변·embedding 생성"]
    Firebase["Firebase Cloud Messaging\npush 전달"]
    Mail["SMTP Provider\n인증·운영 email"]
    GCP["Google Cloud Platform\n실행·DB·비밀·관찰성"]

    Member -->|모바일·웹| System
    Leader -->|모바일| System
    Operator -->|관리자 웹| System
    System -->|token 검증| OAuth
    System -->|근거 구절 기반 요청| OpenAI
    System -->|기기 알림| Firebase
    System -->|email 전달| Mail
    System -->|배포와 운영| GCP
```

## 경계 설명

- My Pasture는 OAuth 공급자가 발급한 token을 검증하지만 공급자의 계정·동의 화면은 소유하지 않습니다.
- OpenAI는 답변을 생성하지만 성경 원문과 최종 citation 허용 여부는 My Pasture 서버가 통제합니다.
- FCM의 요청 수락은 실제 기기 수신을 보장하지 않으므로 전달 lifecycle을 별도로 기록합니다.
- Cloudflare R2는 제품 요구사항에는 있으나 현재 백엔드 구현 경계에는 포함하지 않습니다.

[아키텍처 인덱스로 돌아가기](../README.md)
