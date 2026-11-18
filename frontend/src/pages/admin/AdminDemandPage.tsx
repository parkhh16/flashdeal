import { useEffect, useState } from 'react'
import { adminApi, type DemandNeed, type DemandView, type SearchMatch } from '../../api'
import { useApp } from '../../context/AppContext'

const CATEGORY: Record<string, string> = {
  KEYBOARD: '키보드', MOUSE: '마우스', MONITOR: '모니터', AUDIO: '오디오', ACCESSORY: '액세서리', OTHER: '취급 안 하는 종류',
}
const MATCH: Record<SearchMatch, string> = {
  NONE: '결과 없음', SOME_WORDS: '일부 단어만 맞음', RELAXED: '조건을 풀어서 보여 줌', ALL_WORDS: '모두 맞음',
}
const quote = (qs: string[]) => qs.map(q => `"${q}"`).join(', ')

/** 할 일을 한 줄로. 판매 예측이 아니라 "찾았는데 없던 수요"만 말한다 */
function todo(n: DemandNeed) {
  return n.status === 'NEW'
    ? '우리 가게에 없는 상품이에요. 등록을 검토해 보세요.'
    : `'${n.keywords.join(' ')}'(으)로 찾으면 ${n.products.join(', ')}이(가) 나와요. 손님이 쓴 말로는 못 찾았으니 상품 설명에 그 표현을 넣어 보세요.`
}

export function AdminDemandPage() {
  const { toast, fail } = useApp()
  const [view, setView] = useState<DemandView | null>(null)
  const [busy, setBusy] = useState(false)

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

  return (
    <div className="admin-body">
      {view.demoSearches > 0 && (
        <div className="result-box">
          검색 기록 중 <strong>{view.demoSearches}건</strong>은 화면 시연용으로 만든 데이터예요 (실제 손님의 검색이 아님).
          결과 상태는 실제 검색으로 돌려서 나온 값입니다.
        </div>
      )}

      <section className="panel">
        <div className="panel-head">
          <h3>AI 정리</h3>
          <div className="filter-bar">
            {report && <span className="muted">{new Date(report.createdAt).toLocaleString('ko-KR')} · 최근 {report.days}일 · 못 찾은 검색 {report.failedSearches}건</span>}
            <button className="ghost small" onClick={generate} disabled={busy || !view.aiEnabled}
                    title={view.aiEnabled ? 'Gemini 응답을 기다리므로 수 초 걸립니다' : '서버에 GEMINI_API_KEY가 없어 AI 정리를 할 수 없습니다'}>
              {busy ? '정리하는 중…' : '지금 다시 정리'}
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
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>구분</th><th>손님이 찾던 상품</th><th>검색 횟수</th><th>묶인 검색어</th><th>할 일</th></tr></thead>
              <tbody>
                {report.needs.map(n => (
                  <tr key={n.need}>
                    <td className="nowrap">
                      <span className={`status ${n.status === 'NEW' ? 's-demand-new' : 's-demand-existing'}`}>
                        {n.status === 'NEW' ? '등록 추천' : '있는데 못 찾음'}
                      </span>
                    </td>
                    <td><div className="cell-title">{n.need}</div><div className="muted">{CATEGORY[n.category] ?? n.category}</div></td>
                    <td className="num">{n.searches}</td>
                    <td>{quote(n.queries)}</td>
                    <td>{todo(n)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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
                {view.failed.map(f => (
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
      </section>
    </div>
  )
}
