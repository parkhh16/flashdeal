import { useEffect, useState, type ReactNode } from 'react'
import { adminApi, api, ApiError, type Chaos, type ConcurrencyResult, type Mismatch, type StockStrategy } from '../../api'
import { useApp } from '../../context/AppContext'

function LabCard({ title, purpose, steps, expect, children }: {
  title: string; purpose: string; steps: string[]; expect: string; children: ReactNode
}) {
  return (
    <section className="panel lab">
      <h3>{title}</h3>
      <dl className="lab-guide">
        <dt>무엇을 확인하나요</dt><dd>{purpose}</dd>
        <dt>해보는 방법</dt><dd><ol>{steps.map(s => <li key={s}>{s}</li>)}</ol></dd>
        <dt>기대 결과</dt><dd>{expect}</dd>
      </dl>
      <div className="lab-controls">{children}</div>
    </section>
  )
}

const STRATEGIES: { value: StockStrategy; label: string; desc: string }[] = [
  { value: 'NAIVE', label: '락 없음', desc: '문제 재현용. 동시에 읽고 쓰면서 재고가 덮어써져 초과 판매가 생깁니다.' },
  { value: 'PESSIMISTIC', label: '비관적 락', desc: 'SELECT FOR UPDATE. 정확하지만 요청이 한 줄로 서서 기다립니다.' },
  { value: 'OPTIMISTIC', label: '낙관적 락', desc: 'version 비교 후 충돌 시 재시도. 인기 상품처럼 충돌이 잦으면 재시도가 폭증합니다.' },
  { value: 'ATOMIC_UPDATE', label: '원자적 UPDATE (기본)', desc: 'UPDATE … WHERE stock >= 1. 검사와 차감을 쿼리 한 번에 처리해 가장 빠릅니다.' },
]

const PRESETS: { label: string; desc: string; chaos: Chaos }[] = [
  { label: '정상', desc: '장애 없음', chaos: { latencyMs: 50, failureRate: 0, declineRate: 0, timeoutRate: 0, failAfterPgApproval: false } },
  { label: '카드 거절', desc: '모든 결제가 거절됩니다. 주문은 결제 대기로 돌아가 재결제할 수 있습니다.', chaos: { latencyMs: 50, failureRate: 0, declineRate: 1, timeoutRate: 0, failAfterPgApproval: false } },
  { label: 'PG 응답 지연', desc: 'PG가 승인해놓고 3초 뒤 응답합니다. 우리 서버는 2초에 타임아웃 → 같은 결제 키로 재시도해 이중 결제 없이 승인됩니다.', chaos: { latencyMs: 50, failureRate: 0, declineRate: 0, timeoutRate: 1, failAfterPgApproval: false } },
  { label: 'PG 다운 (5xx)', desc: 'PG가 계속 500을 줍니다. 결제는 "확인 중"으로 남고, 대사에서 PG 기록이 없으면 실패 처리됩니다.', chaos: { latencyMs: 50, failureRate: 1, declineRate: 0, timeoutRate: 0, failAfterPgApproval: false } },
  { label: 'PG 승인 후 우리 DB 장애', desc: '가장 위험한 상황: 돈은 빠졌는데 주문은 결제 중. 결제 대사를 실행하면 PG 기준으로 결제 완료로 복구됩니다.', chaos: { latencyMs: 50, failureRate: 0, declineRate: 0, timeoutRate: 0, failAfterPgApproval: true } },
]

function chaosState(c: Chaos | null) {
  if (!c) return { label: '불러오는 중…', level: 'info' }
  if (c.failAfterPgApproval) return { label: 'PG 승인 직후 우리 DB 반영 실패 (결제 대사로 복구 필요)', level: 'bad' }
  const parts = [
    c.failureRate > 0 && `PG 5xx ${Math.round(c.failureRate * 100)}%`,
    c.declineRate > 0 && `카드 거절 ${Math.round(c.declineRate * 100)}%`,
    c.timeoutRate > 0 && `승인 후 응답 지연 ${Math.round(c.timeoutRate * 100)}%`,
  ].filter(Boolean)
  return parts.length ? { label: parts.join(' · '), level: 'warn' } : { label: '정상 동작 중', level: 'good' }
}

export function AdminLabPage() {
  const { toast, fail, bumpOrders } = useApp()
  const [strategy, setStrategy] = useState<StockStrategy>('ATOMIC_UPDATE')
  const [chaos, setChaos] = useState<Chaos | null>(null)
  const [stock, setStock] = useState(100)
  const [requests, setRequests] = useState(300)
  const [running, setRunning] = useState(false)
  const [concurrency, setConcurrency] = useState<ConcurrencyResult | null>(null)
  const [idemProduct, setIdemProduct] = useState(2)
  const [idemResult, setIdemResult] = useState<string | null>(null)
  const [batchResult, setBatchResult] = useState<string | null>(null)
  const [mismatches, setMismatches] = useState<Mismatch[] | null>(null)

  useEffect(() => {
    api.admin.strategy().then(r => setStrategy(r.data.strategy)).catch(fail)
    api.admin.chaos().then(r => setChaos(r.data)).catch(fail)
  }, [])

  const changeStrategy = async (s: StockStrategy) => {
    try { await api.admin.setStrategy(s); setStrategy(s); toast(`재고 전략을 ${s}로 바꿨습니다.`, 'success') } catch (e) { fail(e) }
  }

  const runConcurrency = async () => {
    setRunning(true)
    setConcurrency(null)
    try { setConcurrency((await adminApi.concurrencyTest(stock, requests)).data) } catch (e) { fail(e) } finally { setRunning(false) }
  }

  const runIdempotency = async () => {
    const key = crypto.randomUUID()
    const results = await Promise.allSettled(Array.from({ length: 5 }, () => api.createOrder(idemProduct, 1, key)))
    const codes = results.map(r => r.status === 'fulfilled'
      ? `${r.value.status}${r.value.replayed ? '(재생)' : ''}`
      : `${(r.reason as ApiError).status}`)
    const created = new Set(results.flatMap(r => r.status === 'fulfilled' ? [r.value.data.orderNo] : []))
    const failed = results.find(r => r.status === 'rejected') as PromiseRejectedResult | undefined
    setIdemResult(created.size > 0
      ? `5회 동시 요청 → 주문 ${created.size}건 생성 · 응답 [${codes.join(', ')}]`
      : `주문 생성 실패: ${(failed?.reason as ApiError)?.message ?? '알 수 없음'}`)
    bumpOrders()
  }

  const applyChaos = async (c: Chaos, label: string) => {
    try { setChaos((await api.admin.setChaos(c)).data); toast(`장애 주입: ${label}`, 'info') } catch (e) { fail(e) }
  }

  const reconcile = async () => {
    try {
      const { data } = await api.admin.reconcile()
      setBatchResult(`결제 대사: 확인 ${data.checked}건 → 결제 완료로 확정 ${data.approved} · 실패로 확정 ${data.failed} · 아직 모름 ${data.stillUnknown}`)
    } catch (e) { fail(e) }
  }
  const expire = async () => {
    try { setBatchResult(`만료 정리: ${(await api.admin.expire()).data.expired}건 만료 처리하고 재고를 돌려놓았습니다.`) } catch (e) { fail(e) }
  }
  const consistency = async () => {
    try { setMismatches((await api.admin.consistency()).data) } catch (e) { fail(e) }
  }

  const state = chaosState(chaos)

  return (
    <div className="admin-body lab-grid">
      <LabCard title="① 재고 차감 전략"
               purpose="재고 100개에 사람이 몰릴 때 초과 판매를 막는 4가지 방법을 바꿔가며 비교합니다."
               steps={['전략을 고른다', '아래 ② 동시성 테스트를 실행한다', '다른 전략으로 바꿔 다시 실행하고 결과를 비교한다']}
               expect="락 없음만 초과 판매가 생기고, 나머지 3개는 0건. 처리 시간은 원자적 UPDATE가 가장 짧습니다.">
        <div className="options">
          {STRATEGIES.map(s => (
            <label key={s.value} className={strategy === s.value ? 'selected' : ''}>
              <input type="radio" name="strategy" checked={strategy === s.value} onChange={() => changeStrategy(s.value)} />
              <span><strong>{s.label}</strong> <code className="muted">{s.value}</code><br /><span className="muted">{s.desc}</span></span>
            </label>
          ))}
        </div>
      </LabCard>

      <LabCard title="② 동시성 테스트"
               purpose="재고 N개인 상품에 M명이 동시에 주문하면, 정확히 N건만 팔리는지 검증합니다."
               steps={['재고와 동시 요청 수를 정한다', '실행하면 숨김 처리된 임시 상품으로 실제 주문 로직을 동시에 호출한다', '끝나면 임시 상품과 주문은 자동으로 지워진다']}
               expect={`예: 재고 100, 동시 300명 → 성공 100 / 품절 200 / 최종 재고 0 / 초과 판매 0건 (현재 전략: ${strategy})`}>
        <div className="inline-form">
          <label>재고 <input type="number" min={1} max={10000} value={stock} onChange={e => setStock(Number(e.target.value))} /></label>
          <label>동시 요청 <input type="number" min={1} max={2000} value={requests} onChange={e => setRequests(Number(e.target.value))} /></label>
          <button onClick={runConcurrency} disabled={running}>{running ? '실행 중…' : '실행'}</button>
        </div>
        {concurrency && (
          <div className={`result-box ${concurrency.consistent ? 'good' : 'bad'}`}>
            <strong>{concurrency.consistent ? '✓ 정합성 유지: 초과 판매 0건' : `✗ 초과 판매 ${concurrency.oversold}건 발생`}</strong>
            <div className="result-nums">
              <span>전략 <b>{concurrency.strategy}</b></span>
              <span>성공 <b>{concurrency.success}</b></span>
              <span>품절 <b>{concurrency.outOfStock}</b></span>
              {concurrency.conflicts + concurrency.errors > 0 && <span>충돌/에러 <b>{concurrency.conflicts + concurrency.errors}</b></span>}
              <span>최종 재고 <b>{concurrency.finalStock}</b></span>
              <span>소요 <b>{concurrency.elapsedMs}ms</b> ({concurrency.throughput} req/s)</span>
            </div>
            {!concurrency.consistent && <p className="muted">{concurrency.success}건이 팔렸는데 재고는 {concurrency.initialStock - concurrency.finalStock}개만 줄었습니다. 동시에 같은 재고를 읽고 덮어썼기 때문입니다 (Lost Update).</p>}
          </div>
        )}
      </LabCard>

      <LabCard title="③ 멱등성 (따닥 방지)"
               purpose="사용자가 버튼을 여러 번 누르거나 네트워크가 재전송해도 주문이 한 번만 생기는지 확인합니다."
               steps={['상품 ID를 정한다 (관리자 계정으로 주문이 생성됨)', '같은 Idempotency-Key로 주문 요청 5개를 동시에 보낸다']}
               expect="주문은 1건만 생성. 나머지 요청은 처음 응답을 그대로 돌려받거나(재생) '처리 중'(409)을 받습니다.">
        <div className="inline-form">
          <label>상품 ID <input type="number" min={1} value={idemProduct} onChange={e => setIdemProduct(Number(e.target.value))} /></label>
          <button onClick={runIdempotency}>5회 동시 요청</button>
        </div>
        {idemResult && <div className="result-box good"><strong>{idemResult}</strong></div>}
      </LabCard>

      <LabCard title="④ 결제 장애 주입"
               purpose="가짜 PG(결제대행사)에 장애를 일으켜, 결제가 꼬여도 돈과 주문이 어긋나지 않는지 확인합니다."
               steps={['장애 유형을 고른다', '다른 창(또는 계정 전환)에서 일반 사용자로 주문하고 결제한다', '⑤에서 결제 대사를 실행하고 주문 상태를 확인한다', '끝나면 "정상"으로 되돌린다']}
               expect="어떤 장애에서도 이중 결제가 없고, 결제 완료인데 주문이 안 된 불일치는 대사로 복구됩니다.">
        <div className={`chaos-state ${state.level}`}>현재 상태: <strong>{state.label}</strong></div>
        <div className="preset-list">
          {PRESETS.map(p => (
            <button key={p.label} className="preset" onClick={() => applyChaos(p.chaos, p.label)}>
              <strong>{p.label}</strong><span>{p.desc}</span>
            </button>
          ))}
        </div>
      </LabCard>

      <LabCard title="⑤ 배치 · 정합성 점검"
               purpose="자동으로 도는 배치(만료 5초, 대사 10초 주기)를 즉시 실행하고, 주문과 결제 금액이 맞는지 점검합니다."
               steps={['결제 대사: 결과를 모르는 결제를 PG에 물어 확정', '만료 정리: 10분 안에 결제 안 한 주문의 재고를 되돌림', '정합성 점검: 결제 완료 주문 ↔ 승인 금액 비교']}
               expect="정합성 점검 결과 불일치 0건.">
        <div className="inline-form">
          <button onClick={reconcile}>결제 대사 실행</button>
          <button onClick={expire}>만료 주문 정리</button>
          <button className="ghost" onClick={consistency}>정합성 점검</button>
        </div>
        {batchResult && <div className="result-box"><strong>{batchResult}</strong></div>}
        {mismatches && (
          mismatches.length === 0
            ? <div className="result-box good"><strong>✓ 주문과 결제 금액 불일치 0건</strong></div>
            : <div className="result-box bad">
                <strong>✗ 불일치 {mismatches.length}건</strong>
                <ul>{mismatches.map(m => <li key={m.orderId}>{m.orderNo} ({m.orderStatus}) 주문 {m.orderAmount}원 / 승인 {m.approvedAmount}원</li>)}</ul>
              </div>
        )}
      </LabCard>
    </div>
  )
}
