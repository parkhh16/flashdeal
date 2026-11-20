// 캡처용 사용 흐름: 데모 회원이 검색·조회·주문·결제하고, 로그인 실패도 한 번
const base = process.env.API || "http://127.0.0.1:8080"; const ONLY = process.env.ONLY;
const j = (r) => r.json().catch(() => ({}));
const login = async (id, pw) => fetch(base + "/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ loginId: id, password: pw }) });
(async () => {
  await login("user1", "wrong-password");
  for (const [id, plan] of [["user1", [[2, 1], [7, 1]]], ["user2", [[12, 1], [21, 2], [16, 1]]]].filter(([u]) => !ONLY || u === ONLY)) {
    const s = await j(await login(id, "user1234!"));
    const h = { Authorization: "Bearer " + s.accessToken, "Content-Type": "application/json" };
    for (const q of ["5만원 이하 무선 마우스", "조용한 키보드", "27인치 모니터", "게이밍 헤드셋"]) await fetch(base + "/api/products/search?q=" + encodeURIComponent(q), { headers: h });
    for (const [pid] of plan) await fetch(base + "/api/products/" + pid, { headers: h });
    for (const [pid, qty] of plan) {
      const o = await j(await fetch(base + "/api/orders", { method: "POST", headers: { ...h, "Idempotency-Key": crypto.randomUUID() }, body: JSON.stringify({ items: [{ productId: pid, quantity: qty }] }) }));
      const p = await fetch(base + `/api/orders/${o.id ?? o.orderId}/payment`, { method: "POST", headers: { ...h, "Idempotency-Key": crypto.randomUUID() } });
      console.log(id, "order", o.orderNo, "payment", p.status);
    }
  }
})();
