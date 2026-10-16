#!/usr/bin/env bash
# 서버 환경 스모크 테스트. 프록시(Caddy)를 거쳐 화면 → 상품 조회 → 가입 → 주문(멱등 재생 포함) → 결제까지 한 바퀴 돈다.
# 필요한 것은 bash, curl, grep, sed 뿐이라 서버에서도 그대로 돌릴 수 있다 (CI는 compose를 띄운 뒤 이 스크립트를 실행한다).
#
#   ./smoke-test.sh                          # https://localhost (자체 인증서라 검증 생략)
#   ./smoke-test.sh https://shop.example.com
#   ADMIN_PASSWORD=... ./smoke-test.sh       # 관리자 로그인까지 확인
set -euo pipefail

BASE="${1:-https://localhost}"
CURL=(curl -sS -k --max-time 15)

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok   $*"; }
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
json_num() { grep -o "\"$1\":[0-9]*" | head -1 | cut -d: -f2; }

# 0. 앱이 준비될 때까지 기다린다 (프록시는 readiness가 UP인 서버에만 요청을 보낸다)
for i in $(seq 1 60); do
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/categories" || true)
  [ "$code" = "200" ] && break
  [ "$i" = "60" ] && fail "앱이 준비되지 않았습니다 (/api/categories → $code)"
  sleep 3
done
ok "프록시 → 앱 연결 (/api/categories 200)"

# 1. 화면: React 빌드 결과와 SPA 라우팅 (없는 경로도 index.html)
"${CURL[@]}" "$BASE/" | grep -q '<div id="root">' || fail "메인 화면이 아닙니다"
"${CURL[@]}" "$BASE/products/1" | grep -q '<div id="root">' || fail "SPA 라우팅이 동작하지 않습니다"
ok "화면 (/ 와 /products/1 모두 index.html)"

# 2. 내부 경로는 프록시하지 않는다
"${CURL[@]}" "$BASE/actuator/health" | grep -q '"status"' && fail "actuator가 외부에 노출되어 있습니다"
ok "actuator, mock-pg는 외부에 노출되지 않음"

# 3. 운영 프로필에는 비밀번호가 공개된 데모 회원이 없다
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"loginId":"user1","password":"user1234!"}' "$BASE/api/auth/login")
[ "$code" = "401" ] || fail "데모 회원 user1로 로그인되었습니다 ($code)"
ok "데모 회원 없음 (user1 로그인 401)"

if [ -n "${ADMIN_PASSWORD:-}" ]; then
  "${CURL[@]}" -H 'Content-Type: application/json' -d "{\"loginId\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" \
    "$BASE/api/auth/login" | grep -q '"accessToken"' || fail "관리자 로그인 실패"
  ok "관리자 로그인 (ADMIN_PASSWORD)"
fi

# 4. 가입 → 주문 → 같은 키로 재요청 → 결제
product=$("${CURL[@]}" "$BASE/api/products?excludeSoldOut=true&sort=PRICE_ASC" | sed -n 's/^\[{"id":\([0-9]*\).*/\1/p')
[ -n "$product" ] || fail "주문할 상품이 없습니다"

login="smoke$(date +%s)"
token=$("${CURL[@]}" -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$login\",\"email\":\"$login@smoke.test\",\"password\":\"smoke1234!\",\"name\":\"smoke\"}" \
  "$BASE/api/auth/signup" | json_str accessToken)
[ -n "$token" ] || fail "회원가입 실패"
ok "회원가입 ($login)"

key="smoke-$(date +%s%N)"
order() {
  "${CURL[@]}" -D - -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d "{\"items\":[{\"productId\":$product,\"quantity\":1}]}" "$BASE/api/orders"
}
first=$(order)
echo "$first" | grep -q '^HTTP/[0-9.]* 201' || fail "주문 실패: $(echo "$first" | tail -1)"
order_id=$(echo "$first" | tail -1 | json_num id)
second=$(order)
echo "$second" | grep -qi '^idempotent-replayed: true' || fail "같은 멱등 키 재요청이 재생되지 않았습니다"
[ "$(echo "$second" | tail -1 | json_num id)" = "$order_id" ] || fail "같은 키로 주문이 두 번 생겼습니다"
ok "주문 #$order_id (같은 Idempotency-Key 재요청은 같은 주문을 재생)"

pay=$("${CURL[@]}" -H "Authorization: Bearer $token" -H "Idempotency-Key: pay-$key" -X POST "$BASE/api/orders/$order_id/payment")
echo "$pay" | grep -q '"status":"APPROVED"' || fail "결제 실패: $pay"
ok "결제 승인 (앱 → 내장 Mock PG, 트랜잭션 밖 HTTP 호출)"

echo "스모크 테스트 통과: $BASE"
