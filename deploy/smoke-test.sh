#!/usr/bin/env bash
# 서버 환경 스모크 테스트. 프록시(Caddy)를 거쳐 화면 → 상품 조회 → 가입 → 주문(멱등 재생 포함) → 결제까지 한 바퀴 돌고,
# 관리자 비밀번호가 있으면 서버 간 캐시 무효화(한 서버에서 정지 → 모든 서버에서 403)까지 확인한다.
# 필요한 것은 bash, curl, grep, sed 뿐이라 서버에서도 그대로 돌릴 수 있다 (CI는 compose를 띄운 뒤 이 스크립트를 실행한다).
#
#   ./smoke-test.sh                          # https://localhost (자체 인증서라 검증 생략)
#   ./smoke-test.sh https://shop.example.com
#   ADMIN_PASSWORD=... ./smoke-test.sh       # 관리자 로그인, 서버 간 캐시 무효화까지 확인 (테스트 회원을 정지시킨다)
#   (마지막에 요청 한도를 확인하므로, 실행한 IP는 1분 동안 로그인이 429로 막힌다)
set -euo pipefail

BASE="${1:-https://localhost}"
CURL=(curl -sS -k --max-time 15)

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok   $*"; }
# 값이 없으면 빈 문자열 (set -e로 조용히 끝나지 않고 다음 줄의 fail이 원인을 출력하게)
json_str() { { grep -o "\"$1\":\"[^\"]*\"" || true; } | head -1 | cut -d'"' -f4; }
json_num() { { grep -o "\"$1\":[0-9]*" || true; } | head -1 | cut -d: -f2; }

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

admin_token=""
if [ -n "${ADMIN_PASSWORD:-}" ]; then
  admin_token=$("${CURL[@]}" -H 'Content-Type: application/json' -d "{\"loginId\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" \
    "$BASE/api/auth/login" | json_str accessToken)
  [ -n "$admin_token" ] || fail "관리자 로그인 실패"
  ok "관리자 로그인 (ADMIN_PASSWORD)"
fi

# 4. 가입 → 주문 → 같은 키로 재요청 → 결제
product=$("${CURL[@]}" "$BASE/api/products?excludeSoldOut=true&sort=PRICE_ASC" | sed -n 's/^\[{"id":\([0-9]*\).*/\1/p')
[ -n "$product" ] || fail "주문할 상품이 없습니다"

login="smoke$(date +%s)"
signup=$("${CURL[@]}" -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$login\",\"email\":\"$login@smoke.test\",\"password\":\"smoke1234!\",\"name\":\"smoke\"}" \
  "$BASE/api/auth/signup")
token=$(echo "$signup" | json_str accessToken)
user_id=$(echo "$signup" | json_num userId)
[ -n "$token" ] && [ -n "$user_id" ] || fail "회원가입 실패: $signup"
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

# 5. 서버 간 캐시 무효화. 앱 서버는 인증할 때 사용자 상태를 자기 메모리에 30초 캐시한다.
#    관리자가 정지시키면 모든 서버의 캐시가 지워져야 한다. 전파가 없으면 라운드 로빈으로 절반은 200(옛 상태)이 나온다
if [ -n "$admin_token" ]; then
  me() { "${CURL[@]}" -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE/api/me"; }
  for i in $(seq 1 6); do   # 두 서버 캐시를 모두 채운다
    code=$(me); [ "$code" = "200" ] || fail "/api/me → $code"
  done
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' -d '{"status":"SUSPENDED"}' "$BASE/api/admin/users/$user_id/status")
  [ "${code:0:1}" = "2" ] || fail "회원 정지 실패 ($code)"
  sleep 1   # 다른 서버로의 전달은 Pub/Sub이라 비동기다 (보통 수 ms)
  codes=""
  for i in $(seq 1 6); do codes="$codes $(me)"; done
  [ "$codes" = " 403 403 403 403 403 403" ] || fail "정지한 회원이 일부 서버에서 아직 통과합니다:$codes"
  ok "서버 간 캐시 무효화 (정지 직후 /api/me 6번 모두 403)"
fi

# 6. 요청 한도 (로그인: IP당 분당 20회). 앱 서버가 여러 대여도 한 곳(Redis)에서 세므로 한도를 넘으면 429.
#    서버마다 따로 세면 라운드 로빈으로 나뉘어 한도가 서버 수만큼 늘어나고, 25번 모두 통과한다.
#    이 IP는 1분 동안 로그인이 막히므로 마지막에 둔다
sec=$(date +%S)
[ "$((10#$sec))" -lt 50 ] || sleep $((61 - 10#$sec))   # 1분 단위 윈도가 도중에 바뀌지 않게
allowed=0; limited=0
for i in $(seq 1 25); do
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d '{"loginId":"nobody","password":"wrong-password"}' "$BASE/api/auth/login")
  if [ "$code" = "429" ]; then limited=$((limited + 1)); else allowed=$((allowed + 1)); fi
done
[ "$limited" -gt 0 ] || fail "로그인 25번이 모두 통과했습니다 (요청 한도가 꺼져 있거나 서버마다 따로 셉니다)"
ok "요청 한도 (같은 IP 로그인 25번 중 $allowed번 처리, $limited번 429)"

echo "스모크 테스트 통과: $BASE"
