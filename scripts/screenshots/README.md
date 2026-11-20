# 화면 캡처 도구

README·문서에 넣을 화면을 같은 조건으로 다시 찍기 위한 스크립트입니다. Node 22 이상과 Chrome만 있으면 되고, 따로 설치할 라이브러리는 없습니다 (Chrome DevTools Protocol을 직접 씁니다).

| 파일 | 하는 일 |
|---|---|
| `shot.mjs` | 헤드리스 Chrome을 띄워 원하는 경로를 원하는 창 너비로 캡처. 관리자로 로그인해서 찍을 수 있고, 가로 넘침(가로 스크롤 생김)을 숫자로 알려 줌 |
| `traffic.js` | 데모 회원(user1, user2)으로 검색·상품 조회·주문·결제를 만들고 로그인 실패도 한 번. 대시보드와 활동 로그가 비어 보이지 않게 |
| `gen.js` | 관리자 "등록 추천"을 실제 Gemini로 생성 (모델이 붐비면 30초 간격으로 다시) |

## 찍기 전에 정할 것

캡처는 한 번 찍으면 README에 오래 남습니다. 찍기 전에 함께 정합니다.

- 어떤 화면을, 어떤 너비로 (데스크톱 1280, 휴대폰 390)
- 화면에 보일 데이터 (데모 회원 주문, 등록 추천 결과). 시연용 데이터는 README에 밝힘
- 사이트 이름·디자인이 곧 바뀌면 찍지 않음 (옛 화면이 남음)

## 순서

```bash
# 1. 서버 (등록 추천을 찍으려면 GEMINI_API_KEY가 있어야 함)
cd backend && ./gradlew bootRun
cd frontend && npm run dev -- --host 127.0.0.1    # 기본은 IPv6(::1)에만 열려서 127.0.0.1로 못 들어옴

# 2. 화면에 보일 데이터
node scripts/screenshots/traffic.js               # ONLY=user1 로 한 명만
node scripts/screenshots/gen.js

# 3. 손님 화면 먼저, 관리자 화면은 나중에 (캡처용 Chrome 프로필에 관리자 로그인이 남기 때문)
MSYS_NO_PATHCONV=1 MAXH=820 node scripts/screenshots/shot.mjs "/?q=..." 1280 C:/경로/shop-search.png
MSYS_NO_PATHCONV=1 MAXH=900 node scripts/screenshots/shot.mjs /admin 1280 C:/경로/admin-dashboard.png admin
MSYS_NO_PATHCONV=1 MAXH=900 node scripts/screenshots/shot.mjs "/admin/activity?userId=3" 1280 C:/경로/admin-activity.png admin
```

## 겪은 함정

| 증상 | 원인 | 대처 |
|---|---|---|
| 관리자 화면이 아니라 첫 화면이 찍힘 ("Cannot navigate to invalid URL") | Git Bash가 `/admin/products` 같은 인자를 Windows 경로로 바꿈 | `MSYS_NO_PATHCONV=1`, 저장 경로는 `C:/...` 형식 |
| 하얀 화면이 찍힘 (파일이 5KB 안팎) | 처음 여는 화면에서 Vite가 준비 작업 후 페이지를 한 번 다시 불러옴 | 같은 명령을 한 번 더 실행. `EVAL='document.getElementById("root").innerText.length'`로 글자 수가 나오는지 확인 |
| 대시보드 "최근 활동"이 관리자 로그인으로 가득 참 | 캡처할 때마다 관리자로 로그인하기 때문 | 찍기 직전에 `traffic.js`를 돌리고, 활동 로그는 `?userId=`로 회원 한 명을 골라 찍음 |
| 손님 화면 위쪽에 "관리자님"이 보임 | 캡처용 Chrome 프로필에 로그인이 남음 | 손님 화면을 먼저 찍거나, 임시 폴더의 `flashdeal-shot-chrome` 프로필을 지움 |
| 브라우저에서 `localhost:5173`이 안 열림 | Vite가 IPv6(`::1`)에만 열림 | `npm run dev -- --host 127.0.0.1` |
