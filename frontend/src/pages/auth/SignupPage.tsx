import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../../api'
import { useApp } from '../../context/AppContext'
import { safeNext } from './LoginPage'

const ID_RULE = /^[a-z0-9_]{4,20}$/
const PW_RULE = /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/
const EMAIL_RULE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type IdState = 'idle' | 'checking' | 'available' | 'taken' | 'invalid'

export function SignupPage() {
  const { session, signup, toast } = useApp()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [form, setForm] = useState({ loginId: '', password: '', confirm: '', name: '', email: '' })
  const [idState, setIdState] = useState<IdState>('idle')
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const next = safeNext(params.get('next'))

  if (session) return <Navigate to={next} replace />

  const set = (k: keyof typeof form, v: string) => {
    setForm(f => ({ ...f, [k]: v }))
    if (k === 'loginId') setIdState('idle')
  }
  const touch = (k: string) => setTouched(t => ({ ...t, [k]: true }))

  /** 입력칸을 벗어날 때 중복 확인. 최종 판단은 서버의 유니크 제약이 한다 (동시에 가입하면 여기서 통과해도 409) */
  const checkId = async () => {
    touch('loginId')
    const id = form.loginId.trim().toLowerCase()
    if (!ID_RULE.test(id)) { setIdState('invalid'); return }
    setIdState('checking')
    try {
      const { data } = await api.checkLoginId(id)
      setIdState(data.available ? 'available' : 'taken')
    } catch {
      setIdState('idle')
    }
  }

  const errors = {
    loginId: !ID_RULE.test(form.loginId.trim().toLowerCase()) ? '영문 소문자, 숫자, _ 조합 4~20자' : idState === 'taken' ? '이미 사용 중인 아이디입니다' : null,
    password: !PW_RULE.test(form.password) ? '영문과 숫자를 포함해 8자 이상' : null,
    confirm: form.confirm !== form.password ? '비밀번호가 일치하지 않습니다' : null,
    name: !form.name.trim() ? '이름을 입력해주세요' : null,
    email: !EMAIL_RULE.test(form.email) ? '올바른 이메일 형식이 아닙니다' : null,
  }
  const valid = Object.values(errors).every(e => e === null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTouched({ loginId: true, password: true, confirm: true, name: true, email: true })
    if (!valid) return
    setError(null)
    setSubmitting(true)
    try {
      const s = await signup({ loginId: form.loginId.trim().toLowerCase(), password: form.password, name: form.name.trim(), email: form.email.trim() })
      toast(`${s.name}님, 가입을 환영합니다!`, 'success')
      navigate(next, { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.code === 'A003') setIdState('taken')
      setError(err instanceof ApiError ? err.message : '가입하지 못했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  const hint = (k: keyof typeof errors) => touched[k] && errors[k]
    ? <span className="field-error">{errors[k]}</span> : null

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={submit} noValidate>
        <h2>회원가입</h2>
        <label>아이디
          <input autoFocus autoComplete="username" value={form.loginId} onChange={e => set('loginId', e.target.value)} onBlur={checkId}
                 aria-invalid={touched.loginId && !!errors.loginId} />
          {idState === 'checking' && <span className="muted">확인 중…</span>}
          {idState === 'available' && !errors.loginId && <span className="field-ok">사용할 수 있는 아이디입니다</span>}
          {hint('loginId')}
        </label>
        <label>비밀번호
          <input type="password" autoComplete="new-password" value={form.password} onChange={e => set('password', e.target.value)} onBlur={() => touch('password')}
                 aria-invalid={touched.password && !!errors.password} />
          {hint('password') ?? <span className="muted">영문과 숫자를 포함해 8자 이상</span>}
        </label>
        <label>비밀번호 확인
          <input type="password" autoComplete="new-password" value={form.confirm} onChange={e => set('confirm', e.target.value)} onBlur={() => touch('confirm')}
                 aria-invalid={touched.confirm && !!errors.confirm} />
          {hint('confirm')}
        </label>
        <label>이름
          <input autoComplete="name" maxLength={50} value={form.name} onChange={e => set('name', e.target.value)} onBlur={() => touch('name')} />
          {hint('name')}
        </label>
        <label>이메일
          <input type="email" autoComplete="email" value={form.email} onChange={e => set('email', e.target.value)} onBlur={() => touch('email')} />
          {hint('email')}
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button type="submit" className="auth-submit" disabled={submitting}>{submitting ? '가입 중…' : '가입하기'}</button>
        <p className="auth-alt">이미 회원이신가요? <Link to={`/login${next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`}>로그인</Link></p>
      </form>
    </div>
  )
}
