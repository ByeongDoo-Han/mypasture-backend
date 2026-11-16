# 개역한글판 PDF 본문

개역한글판은 `KRV`로 저장합니다. 1910 원본 `KOR1910`과 별개의 번역본이며 66권·1,189장·31,101절을 포함합니다. 원본은 사용자가 제공한 599쪽 `개역한글.sean.night.HD.24in.grey@br.pdf`입니다. PDF 파일과 편집 레이아웃은 저장소나 앱에 포함하지 않고, 장·절 텍스트만 `src/main/resources/bible/krv.json`에 포함합니다. 대한성서공회에 따르면 『성경전서 개역한글판』(1961)의 저작재산권 보호기간은 2011년 12월 31일 종료되었습니다. 본문 출처 표시는 유지합니다.

`scripts/extract-krv-pdf.py --pdf <PDF 경로> --reference-db <앱 kor1910.db 경로>`로 재생성합니다. 기존 DB는 두 단 PDF의 줄바꿈으로 갈라진 단어의 띄어쓰기 대조에만 사용합니다. 스크립트는 전체 599쪽과 모든 권·장·절의 연속 번호를 검사하며, 결과에 원본 PDF SHA-256을 남깁니다. 원본이 바뀌면 생성물·대표 구절을 다시 검수하고 모바일 정적 파일의 revision을 올립니다.

서버는 `APP_BIBLE_KRV_IMPORT_ENABLED=true`로 명시적으로 실행할 때만 새 번역본을 DB에 가져옵니다. import가 끝나기 전에는 `/api/v1/bibles/versions` 목록에 KRV를 표시하지 않습니다. 다중 인스턴스에서는 PostgreSQL advisory lock과 단일 트랜잭션으로 중복 import를 막으며 기존 절 ID를 유지합니다. 운영 환경에서는 서버 import와 API 조회를 먼저 확인하고, 웹 정적 본문을 배포한 다음 앱을 출시합니다. 이후에는 import 환경변수를 다시 끌 수 있습니다. 기존 `KOR1910` import 설정은 독립적으로 유지됩니다.
