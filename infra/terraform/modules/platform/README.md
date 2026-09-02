# Platform module

My Pasture Spring Boot API와 singleton scheduler worker를 GCP에 배치하는 내부 모듈입니다.

- API 인스턴스에서는 사용자 요청과 QT SSE heartbeat만 처리합니다.
- 정기 알림, 오늘의 말씀, 정리 작업은 worker 한 인스턴스에서 실행합니다.
- Flyway는 배포 전에 Cloud Run migration Job에서 실행합니다.
- Redis는 외부 IP가 없는 Container-Optimized OS VM에서 TLS/AUTH Docker 컨테이너로 실행합니다.
- Redis VM은 AOF를 사용하지만 단일 노드이므로 PostgreSQL을 대신하는 데이터 원본으로 사용하지 않습니다.
- DB 사용자와 secret payload는 Terraform 밖에서 생성합니다.
- 운영 API는 HTTPS Load Balancer와 Cloud Armor를 통과하며 직접 `run.app` 접근은 ingress에서 차단합니다.
- Next.js Admin은 별도 Cloud Run에서 실행하고 direct IAP와 백엔드 `ADMIN` 역할로 이중 보호합니다.
- Admin runtime service account에는 Cloud SQL, Redis와 Secret Manager 권한을 부여하지 않습니다.

Redis 전환 중에는 `redis_backend = "vm"`, `provision_redis_vm = true`, `retain_memorystore = true`로 VM을 활성 백엔드로 선택하면서 기존 Memorystore를 보존할 수 있습니다. TLS PING과 애플리케이션 E2E를 확인한 후 별도 승인된 변경에서 `retain_memorystore = false`로 제거합니다.
