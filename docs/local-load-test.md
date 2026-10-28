# 앱 요청 기준 로컬 부하 테스트

네이티브 앱의 KOR1910/KRV 본문과 검색은 기기 SQLite에서 처리합니다. 이 문서의 서버 부하 테스트에는 성경 장 조회를 넣지 않습니다. 앱을 처음 켜거나 본문이 교정됐을 때 받는 공개 manifest/권 파일은 정적 호스팅 경로이고, 읽기 진도·메모·QT·AI는 API 요청입니다. SQLite 열기·장 전환·검색 시간은 실제 iOS/Android 기기에서 별도로 측정해야 합니다.

## 실행 환경

Docker Compose, k6, Python 3, Node.js가 필요합니다. 이 구성은 Docker Compose v2.34.0, k6 v1.3.0, Node.js v22에서 확인했습니다.

저장소 루트에서 전용 Compose 프로젝트의 PostgreSQL, Redis, backend를 시작합니다. 기존 개발 컨테이너와 이름·볼륨·포트가 겹치지 않도록 `mypasture-load` 프로젝트와 별도 포트(18080/15432/16379)를 사용합니다. `backend`는 `local` 프로필, `AI_MOCK_ENABLED=true`, 이메일 인증번호 로그 전달이 기본값입니다. 이 테스트 스크립트는 `localhost`/`127.0.0.1` 외의 주소를 거부합니다. Redis는 현재 Compose의 backend 의존 서비스로 함께 뜨며 이 부하 시나리오가 Redis를 직접 측정하는 것은 아닙니다.

```bash
docker compose -p mypasture-load -f docker-compose.yml -f scripts/load/compose.load.yml up -d --build postgres redis backend
docker compose -p mypasture-load -f docker-compose.yml -f scripts/load/compose.load.yml ps
python3 scripts/load/prepare-local-users.py --users 1
MODE=reading k6 run scripts/load/app-api.js
```

`prepare-local-users.py`는 실제 회원가입·정책 동의·로그인 API를 거쳐 사용자마다 토큰 하나를 만듭니다. 인증번호는 로컬 backend 로그에서 읽고 출력하지 않습니다. 토큰은 Git 무시 대상인 `scripts/load/tokens.local.json`에 권한 600으로 저장합니다. 파일을 공유하거나 커밋하지 마세요. 기본 액세스 토큰 수명(15분)이 지나면 파일을 삭제하고 새 계정을 준비합니다. 반복 실행으로 만든 계정은 로컬 DB에 남으므로 전용 Compose 볼륨에서만 실행하세요. 기존 토큰 파일이 있으면 명시적으로 지운 뒤 다시 실행합니다.

## 시나리오

기본값은 VU 1명·1회 반복입니다. VU마다 서로 다른 토큰을 사용하므로 동시 사용자 수만큼 로컬 계정을 준비합니다. `VUS`는 최대 200, `ITERATIONS`는 VU당 최대 100입니다. 읽기 진도는 동일한 장의 동일 절을 갱신하므로 매 반복마다 새 기록을 무한히 늘리지 않습니다.

`PROFILE=steady`는 지정한 VU 수를 10초~3분 유지합니다. 10명 단기 확인 다음에는 30→50→100명을 **각각 별도 실행**해 오류율·p95와 컨테이너 CPU/메모리를 비교합니다. 기본 반복 간격은 1초이며 `THINK_SECONDS`로 1~30초를 지정할 수 있습니다. 아래 1초 간격의 반복은 병목 탐색을 위해 실제 읽기 속도보다 빠르게 요청하므로 운영 트래픽 예측치로 해석하지 않습니다. 계정별 액세스 토큰 만료 전에 세 실행을 마치거나 새 계정을 준비합니다.

```bash
python3 scripts/load/prepare-local-users.py --users 100
MODE=reading PROFILE=steady VUS=30 DURATION=60s k6 run scripts/load/app-api.js
MODE=reading PROFILE=steady VUS=50 DURATION=60s k6 run scripts/load/app-api.js
MODE=reading PROFILE=steady VUS=100 DURATION=60s k6 run scripts/load/app-api.js
```

AI SSE는 사용자당 이용 한도가 있어 지속 실행을 차단합니다. 모의 AI로 동시 요청 수만 바꿔 1회씩 확인하고, 실행별 `AI_RUN_ID`를 바꿔 캐시 적중과 새 생성 요청을 구분합니다. 실제 OpenAI 연동은 소량의 별도 통합 검사로 측정합니다. QT SSE는 `SSE_VUS`로 토큰 수 이하의 동시 연결 수를 지정하고 `qt-sse.mjs`로 유지 상태와 답변 이벤트 수를 측정합니다.

2026-10-28에 실행한 30·50·100명 측정 조건과 결과는 [검증 기록](load-test-results-2026-10-28.md)에 남겼습니다.

| 명령 | 실제 요청 | 준비 사항 |
| --- | --- | --- |
| `MODE=reading VUS=10 ITERATIONS=3 k6 run scripts/load/app-api.js` | 내 진도 GET + 창세기 1:1 진도 PUT | `--users 10`으로 계정 준비 |
| `MODE=qt-read QT_SESSION_ID=<UUID> k6 run scripts/load/app-api.js` | 모임 답변 GET | 아래 QT 준비 스크립트 실행 |
| `MODE=qt-write QT_SESSION_ID=<UUID> k6 run scripts/load/app-api.js` | 관찰 질문 첫 번째 답변 PUT | 아래 QT 준비 스크립트 실행. 필요하면 `QT_CATEGORY`, `QT_QUESTION_INDEX` 지정 |
| `MODE=ai-stream AI_MOCK_CONFIRMED=1 k6 run scripts/load/app-api.js` | AI 목자 POST SSE 완료까지 | 로컬 backend의 `AI_MOCK_ENABLED=true` 확인, 사용자별 이용 한도 확인 |

QT 모임은 로컬 준비 스크립트가 일회성 모임을 만든 뒤 모든 테스트 계정을 참여시킵니다. UUID는 Git 무시 대상 파일에 저장합니다. `qt-write`는 각 계정의 같은 질문 답변을 갱신합니다. 서로 다른 사용자 토큰을 쓰면 한 계정의 동시 수정 경합을 성능 수치에 섞지 않습니다. 빈 모임이나 미참여 계정을 사용하면 403/404가 정상적으로 발생하므로 부하 결과로 해석하지 않습니다.

```bash
python3 scripts/load/prepare-local-qt.py
QT_SESSION_ID="$(cat scripts/load/qt-session.local.txt)" MODE=qt-read k6 run scripts/load/app-api.js
```

AI 시나리오는 **모의 응답만** 대상으로 합니다. `AI_MOCK_CONFIRMED=1`을 명시해야 실행됩니다. HTTP 요청이 끝날 때까지 기다려 전체 SSE 응답과 `done` 이벤트를 검증하므로 응답 전체 시간은 측정하지만 첫 토큰 시간은 측정하지 않습니다. 실제 OpenAI 지연·토큰 비용은 작은 수의 별도 통합 검사에서 확인하며 이 부하 시나리오에 연결하지 않습니다.

QT 답변 SSE는 연결을 오래 유지하므로 별도 스크립트로 확인합니다. 기본 Node.js의 `fetch`를 사용해 각 사용자로 연결하고 `connected` 이벤트를 확인한 뒤 15초 유지합니다.

```bash
QT_SESSION_ID="$(cat scripts/load/qt-session.local.txt)" SSE_SECONDS=15 node scripts/load/qt-sse.mjs
```

이 스크립트는 설정한 연결 수, 실제 연결 수, 조기 종료, 오류를 출력합니다. k6 기본 HTTP 모듈만으로는 지속되는 SSE 이벤트를 읽으며 연결을 유지하는 검증을 하기 어렵기 때문에 분리했습니다.

## 결과 해석

- `app_api_failures`는 예상 HTTP 상태와 AI `done` 이벤트의 실패 비율입니다. 401은 만료된 토큰, 403은 모임 권한이나 정책 동의, 429는 AI 일일 한도 등을 먼저 확인합니다.
- k6의 `http_req_duration`은 API 전체 처리 시간입니다. AI 모의 응답에서 측정한 값은 실제 OpenAI 응답 시간을 포함하지 않습니다.
- Docker Desktop의 CPU·메모리 제한, 로컬 DB 데이터량, Mac의 백그라운드 작업을 기록하고 같은 조건에서 변경 전후를 비교합니다. 이 수치를 Cloud Run 최대 동시 사용자 수로 직접 환산하지 않습니다.
- 실제 사용 비율은 앱 한 세션의 네트워크 기록으로 확인한 뒤 `reading`, QT, AI 실행 비율에 반영합니다. 100명이 SQLite 성경을 읽는다고 서버 본문 GET 100건을 추가하지 않습니다.

실행 후 토큰 파일과 세션 ID 파일을 삭제하고 전용 컨테이너를 중지합니다. 아래 `down`은 볼륨을 보존합니다. 전용 로컬 DB를 더 이상 쓰지 않을 때만 별도로 해당 Compose 볼륨을 제거합니다.

```bash
rm scripts/load/tokens.local.json scripts/load/qt-session.local.txt
docker compose -p mypasture-load -f docker-compose.yml -f scripts/load/compose.load.yml down
```
