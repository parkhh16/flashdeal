(async () => {
  const base = process.env.API || "http://127.0.0.1:8080";
  const login = await (await fetch(base + "/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ loginId: "admin", password: "admin" }) })).json();
  const h = { Authorization: "Bearer " + login.accessToken };
  for (let attempt = 1; attempt <= 5; attempt++) {
    const t = Date.now();
    const r = await fetch(base + "/api/admin/demand/report", { method: "POST", headers: h });
    const rep = await r.json();
    console.log(`try ${attempt}: ${r.status} ${Date.now() - t}ms`);
    if (r.status === 200) {
      rep.needs.forEach(n => console.log(n.status.padEnd(8), "|", n.need, "|", n.category, "|", JSON.stringify(n.phrases), "|", n.queries.join(", "), "| 있음:", n.products.join(", "), "| 후보:", (n.similar || []).join(", ")));
      return;
    }
    await new Promise(res => setTimeout(res, 30000));
  }
})();
