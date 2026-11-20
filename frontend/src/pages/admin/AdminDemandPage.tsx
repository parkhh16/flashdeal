import { useEffect, useState } from 'react'
import { adminApi, type DemandNeed, type DemandView, type SearchMatch } from '../../api'
import { useApp } from '../../context/AppContext'

const CATEGORY: Record<string, string> = {
  KEYBOARD: '키보드', MOUSE: '마우스', MONITOR: '모니터', AUDIO: '오디오', ACCESSORY: '액세서리', OTHER: '취급 안 하는 종류',
}
const MATCH: Record<SearchMatch, string> = {
  NONE: '결과 없음', SOME_WORDS: '일부 단어만 맞음', RELAXED: '조건을 풀어서 보여 줌', ALL_WORDS: '모두 맞음',
}
const FAILED_PREVIEW = 15
const quote = (qs: string[]) => qs.map(q => `"${q}"`).join(', ')

/**
 * 구분과 할 일. 판매 예측이 아니라 "찾았는데 없던 수요"만 말하고, AI 표현이 우리 표기와 다를 수 있어서 "없다"고 단정하지 않는다
 */
function verdict(n: DemandNeed): { label: string; cls: string; todo: string } {
  const similar = n.similar ?? []
  if (n.status === 'EXISTING') {
    return {
      label: '있는데 못 찾음', cls: 's-demand-existing',
      todo: `있는 상품: ${n.products.join(', ')}. 손님이 쓴 말로는 안 나오니 상품 설명에 그 표현을 넣어 보세요.`,
    }
  }
  if (similar.length > 0) {
    return {
      label: '확인 필요', cls: 's-demand-check',
      todo: `딱 맞는 상품은 없고 비슷한 상품이 있어요: ${similar.join(', ')}. 같은 상품이면 설명에 손님 표현을 넣고, 아니면 등록을 검토해 보세요.`,
    }
  }
  return { label: '등록 추천', cls: 's-demand-new', todo: '판매자 표현으로 검색해도 맞는 상품이 없어요. 등록을 검토해 보세요.' }
}

export function AdminDemandPage() {
  const { toast, fail } = useApp()
  const [view, setView] = useState<DemandView | null>(null)
  const [busy, setBusy] = useState(false)
  const [showAll, setShowAll] = useState(false)

  const load = () => adminApi.demand().then(r => setView(r.data)).catch(fail)
  useEffect(() => { load() }, [])

  const generate = async () => {
    setBusy(true)
    try {
      await adminApi.generateDemand()
      toast('못 찾은 검색어를 다시 정리했습니다.', 'success')
      await load()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  if (!view) return <div className="admin-body"><p className="muted">불러오는 중…</p></div>
  const report = view.report
  const grouped = new Set(report?.needs.flatMap(n => n.queries) ?? [])
  // AI가 어느 묶음에도 넣지 않은 검색어. 숨기지 않고 보여 준다 (AI가 빠뜨린 것인지 판매자가 확인할 수 있게)
  const ungrouped = report ? view.failed.filter(f => !grouped.has(f.query)).slice(0, 10) : []
  const failed = showAll ? view.failed : view.failed.slice(0, FAILED_PREVIEW)

  return (
    <div className="admin-body">
      {view.demoSearches > 0 && (
        <div className="result-box">
          검색 기록 중 <strong>{view.demoSearches}건</strong>은 화면 시연용으로 만든 데이터예요 (실제 손님의 검색이 아님).
          결과 상태는 실제 검색으로 돌려서 나온 값입니다.
        </div>
      )}

      <section className="panel">
        <div className="panel-head demand-head">
          <h3>AI 정리</h3>
          <div className="demand-actions">
            {report && <span className="muted">{new Date(report.createdAt).toLocaleString('ko-KR')} · 최근 {report.days}일 · 못 찾은 검색 {report.failedSearches}건</span>}
            <button className="ghost small" onClick={generate} disabled={busy || !view.aiEnabled}
                    title={view.aiEnabled ? 'Gemini 응답을 기다리므로 수 초 걸립니다' : '서버에 GEMINI_API_KEY가 없어 AI 정리를 할 수 없습니다'}>
              {busy ? '정리하는 중… (수 초)' : '지금 다시 정리'}
            </button>
          </div>
        </div>
        {!report && (
          <p className="muted">
            아직 정리한 결과가 없어요. 매일 새벽 4시에 정리하고, 버튼으로 바로 정리할 수도 있어요.
            {!view.aiEnabled && ' (지금은 AI 키가 없어 아래의 집계만 볼 수 있어요.)'}
          </p>
        )}
        {report && report.needs.length === 0 && <p className="muted">정리할 만한 못 찾은 검색이 없었어요.</p>}
        {report && report.needs.length > 0 && (
          <ul className="demand-list">
            {report.needs.map(n => {
              const v = verdict(n)
              return (
                <li key={n.need}>
                  <div className="demand-title">
                    <span className={`status ${v.cls}`}>{v.label}</span>
                    <strong>{n.need}</strong>
                    <span className="muted">{CATEGORY[n.category] ?? n.category}</span>
                    <span className="demand-count">검색 {n.searches}회</span>
                  </div>
                  <div className="muted">손님 검색어: {quote(n.queries)}</div>
                  <div>{v.todo}</div>
                  <div className="muted small-note">확인한 판매자 표현: {quote(n.phrases)}</div>
                </li>
              )
            })}
          </ul>
        )}
        {ungrouped.length > 0 && (
          <p className="muted small-note">
            AI가 어느 묶음에도 넣지 않은 검색어: {quote(ungrouped.map(f => f.query))}
          </p>
        )}
      </section>

      <section className="panel">
        <div className="panel-head">
          <h3>못 찾은 검색어</h3>
          <span className="muted">최근 {view.days}일 · AI 없이 검색 기록에서 센 값</span>
        </div>
        {view.failed.length === 0 && <p className="muted">못 찾은 검색이 없어요.</p>}
        {view.failed.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>검색어</th><th>횟수</th><th>최근 결과</th></tr></thead>
              <tbody>
                {failed.map(f => (
                  <tr key={f.query}>
                    <td>{f.query}</td>
                    <td className="num">{f.searches}</td>
                    <td className="nowrap muted">{MATCH[f.lastMatch] ?? f.lastMatch}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {view.failed.length > FAILED_PREVIEW && (
          <button className="ghost small more-button" onClick={() => setShowAll(s => !s)}>
            {showAll ? '접기' : `${view.failed.length - FAILED_PREVIEW}개 더 보기`}
          </button>
        )}
      </section>
    </div>
  )
}
