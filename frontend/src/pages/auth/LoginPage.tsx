import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../../api'
import { useApp } from '../../context/AppContext'

/** 외부 사이트로 튕겨 나가지 않게, 앱 내부 경로만 next로 허용한다 (오픈 리다이렉트 방지) */
export function safeNext(next: string | null) {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
}

const DEMO = [
  { label: '일반 회원 (김철수)', id: 'user1', pw: 'user1234!' },
  { label: '일반 회원 (이영희)', id: 'user2', pw: 'user1234!' },
  { label: '관리자', id: 'admin', pw: 'admin' },
]

export function LoginPage() {
  const { session, login, toast } = useApp()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const next = safeNext(params.get('next'))

  if (session) return <Navigate to={next} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const s = await login(loginId, password)
      toast(`${s.name}님, 환영합니다.`, 'success')
      navigate(next === '/login' ? '/' : next, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '로그인하지 못했습니다.')
      setPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={submit} noValidate>
        <h2>로그인</h2>
        <label>아이디 또는 이메일
          <input autoFocus autoComplete="username" value={loginId} onChange={e => setLoginId(e.target.value)} required />
        </label>
        <label>비밀번호
          <input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button type="submit" className="auth-submit" disabled={submitting || !loginId || !password}>
          {submitting ? '로그인 중…' : '로그인'}
        </button>
        <p className="auth-alt">아직 회원이 아니신가요? <Link to={`/signup${next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`}>회원가입</Link></p>

        <div className="demo-box">
          <strong>데모 계정</strong>
          <span className="muted">누르면 입력칸이 채워집니다. 로그인 버튼은 직접 눌러주세요.</span>
          <div className="demo-list">
            {DEMO.map(d => (
              <button type="button" key={d.id} className="ghost small" onClick={() => { setLoginId(d.id); setPassword(d.pw); setError(null) }}>
                {d.label} <code>{d.id}</code>
              </button>
            ))}
          </div>
        </div>
      </form>
    </div>
  )
}
