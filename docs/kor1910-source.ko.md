# KOR1910 원문 출처와 보강 기록

## 기본 원문과 사용 조건

- 저장소의 `data/kor_html`은 eBible의 `KOROLD`(Korean Bible 1910) HTML을 사용합니다.
- eBible 상세 페이지는 이 번역을 Public Domain으로 표시하며 기여자를 레널즈, 이승두, 김정삼으로 기록합니다.
- 기준 페이지: <https://ebible.org/details.php?id=kor>

## 2026-10-09 창세기 누락 보강

eBible에서 받은 최신 HTML, USFM, VPL을 다시 확인했지만 세 형식 모두 GEN30:32~43과 GEN33:20이 빠져 있었습니다. 번역본을 바꾸지 않고 CrossWire의 Public Domain `KorRV` 모듈에서 해당 13절을 가져와 두 HTML 장에만 추가했습니다. 대한성서공회 개역한글 페이지는 장·절과 본문이 같은지 독립적으로 대조하는 데만 사용했습니다.

- CrossWire `KorRV` 정보와 Public Domain 표시: <https://crosswire.org/sword/modules/ModInfo.jsp?modName=KorRV>
- CrossWire `KorRV.zip` SHA-256: `194dd52ceabdefb97a60f6627de22e97990e0a43b1b388caf4013d010884ecd7`
- 독립 대조: [대한성서공회 개역한글 창세기 30장](https://www.bskorea.or.kr/bible/korbibReadpage.php?book=gen&chap=30&version=HAN), [33장](https://www.bskorea.or.kr/bible/korbibReadpage.php?book=gen&chap=33&version=HAN)
- 내려받은 eBible archive SHA-256
  - `kor_html.zip`: `037c07fcf8a3847735538b62a23bf2e9fe8d51ca97f3df3bb34eeed3a5220561`
  - `kor_usfm.zip`: `30f1978ebbad2716f295987b6423d2ae4ba2b2f6cb758f50bf28f20960f846fa`
  - `kor_vpl.zip`: `e687cf8142c55ac572efd448186659c3d81144e7e30dd13d418161d486969dff`
- 보강 후 장 파일 SHA-256
  - `GEN30.htm`: `a79ad0b49b56e32644593fd4a912eaa78658e4c2a138f699816ec45f158993d8`
  - `GEN33.htm`: `4f11e7fcf1c985d809cffa5fd3a03d857f397fd3b4047ca7ae347c00e279f309`

보강 뒤 GEN1~50은 장별 절 번호가 1부터 끊김 없이 이어지고 총 1,533절입니다. 애플리케이션의 멱등 importer는 기존 논리 키 `(version, book_id, chapter, verse)`의 행을 갱신하고 누락된 키만 추가하므로 기존 절 ID, 외래 키와 이미 게시된 말씀 snapshot은 유지됩니다.
