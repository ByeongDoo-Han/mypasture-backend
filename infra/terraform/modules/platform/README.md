# Platform module

My Pasture Spring Boot API와 singleton scheduler worker를 GCP에 배치하는 내부 모듈입니다.

- API 인스턴스에서는 사용자 요청과 QT SSE heartbeat만 처리합니다.
- 정기 알림, 오늘의 말씀, 정리 작업은 worker 한 인스턴스에서 실행합니다.
- Flyway는 배포 전에 Cloud Run migration Job에서 실행합니다.
- DB 사용자와 secret payload는 Terraform 밖에서 생성합니다.
- 운영 API는 HTTPS Load Balancer와 Cloud Armor를 통과하며 직접 `run.app` 접근은 ingress에서 차단합니다.
