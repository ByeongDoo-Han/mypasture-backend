# 이벤트 꾸미기 보상 코드 정책

## 결정

- 자체 발급 코드는 유료 기능, AI 횟수, 구독 등급, 디지털 화폐를 해제하지 않습니다.
- 현재 허용 보상은 무료·비구매·비양도 목장 꾸미기 아이템 `PASTURE_DECORATION_ITEM`뿐입니다.
- 앱 안팎에서 판매한 코드로 디지털 아이템을 지급하지 않습니다. 향후 꾸미기 아이템을 판매하면 Apple In-App Purchase와 Google Play Billing 상품으로 전환합니다.
- 코드는 신앙 성과, 출석, XP, 경쟁 순위에 영향을 주지 않습니다.

Apple 심사 지침 3.1.1은 자체 코드나 QR로 앱 기능 또는 디지털 상품을 해제하는 방식을 제한합니다. Google Play도 앱 안에서 소비되는 디지털 기능·아이템의 유료 거래에는 Play Billing을 요구합니다.

- Apple: https://developer.apple.com/app-store/review/guidelines/#in-app-purchase
- Google Play: https://support.google.com/googleplay/android-developer/answer/9858738

## 발급·등록 정책

- 공유 코드는 여러 사용자가 캠페인 전체 한도까지 등록할 수 있고, 개별 코드는 코드별 한 번만 등록됩니다.
- `eligibilityKey`가 같은 이벤트는 계정당 평생 한 번만 받을 수 있습니다.
- 캠페인에는 등록 시작·종료 시각, 전체 수량, 아이템 코드와 지급 수량을 둡니다.
- 지급 수량은 1~100개, 아이템 코드는 서버가 허용하는 안정적인 영문 식별자를 사용합니다.
- 보상 자체에는 만료를 두지 않습니다. 캠페인 등록 기간만 만료됩니다.

## 보안과 운영

- 코드 원문은 DB와 로그에 저장하지 않고 서버 비밀값으로 HMAC-SHA256 처리합니다.
- 사용자 단위 rate limit, 캠페인·코드 행 잠금, DB unique constraint로 중복 지급을 막습니다.
- 생성 응답의 개별 코드 원문은 다시 조회할 수 없으므로 승인된 운영 채널에서 한 번만 전달합니다.
- 유출 시 캠페인을 비활성화하며, 이미 지급된 보상 회수는 별도 감사 기능이 마련되기 전에는 수행하지 않습니다.
- `V39__coupon_collectible_rewards.sql`은 기존 프리미엄 AI 체험 캠페인을 비활성화하고 쿠폰 출처의 AI 체험 권한을 폐기합니다.

## 환경변수

- `COUPON_CODE_PEPPER`: 32바이트 이상의 무작위 서버 비밀값
- `COUPON_REDEEM_RATE_LIMIT_PER_WINDOW`: 기본 60초당 사용자 8회
