import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, type Me } from '../../api'
import { useApp } from '../../context/AppContext'

export function AccountPage() {
  const { logout, toast, fail } = useApp()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [password, setPassword] = useState('')
  const [agree, setAgree] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => { api.me().then(r => setMe(r.data)).catch(fail) }, [])

  const logoutAll = async () => {
    if (!window.confirm('이 기기를 포함해 로그인된 모든 기기에서 로그아웃할까요?')) return
    try {
      await api.logoutAll()
      logout()
      toast('모든 기기에서 로그아웃했습니다.', 'success')
      navigate('/login')
    } catch (e) { fail(e) }
  }

  const withdraw = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api.withdraw(password)
      logout()
      toast('탈퇴가 완료되었습니다. 그동안 이용해주셔서 감사합니다.', 'success')
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '탈퇴하지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="account-page">
      <div className="shop-head"><h2>계정 설정</h2></div>

      <section className="panel">
        <h3>내 정보</h3>
        {me && (
          <dl className="facts">
            <dt>아이디</dt><dd>{me.loginId}</dd>
            <dt>이름</dt><dd>{me.name}</dd>
            <dt>이메일</dt><dd>{me.email}</dd>
            <dt>가입일</dt><dd>{new Date(me.createdAt).toLocaleDateString('ko-KR')}</dd>
            <dt>최근 로그인</dt><dd>{me.lastLoginAt ? new Date(me.lastLoginAt).toLocaleString('ko-KR') : '-'}</dd>
          </dl>
        )}
      </section>

      <section className="panel">
        <h3>보안</h3>
        <p className="muted">다른 기기(PC방, 친구 폰 등)에서 로그아웃을 깜빡했다면 모든 기기에서 한 번에 로그아웃할 수 있어요.</p>
        <button className="ghost" onClick={logoutAll}>모든 기기에서 로그아웃</button>
      </section>

      {me?.role !== 'ADMIN' && (
        <section className="panel danger-zone">
          <h3>회원 탈퇴</h3>
          <ul className="notice">
            <li>탈퇴하면 이름, 이메일, 아이디 등 개인정보가 즉시 파기되고 복구할 수 없어요.</li>
            <li>주문·결제 기록은 전자상거래법에 따라 5년간 보관되며, 개인을 알아볼 수 없는 형태로 남아요.</li>
            <li>결제 대기 중이거나 결제 확인 중인 주문이 있으면 탈퇴할 수 없어요.</li>
            <li>탈퇴 후 같은 아이디로 다시 가입할 수 있어요.</li>
          </ul>
          <form className="inline-form" onSubmit={withdraw}>
            <label>비밀번호 확인
              <input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} />
            </label>
            <label className="check"><input type="checkbox" checked={agree} onChange={e => setAgree(e.target.checked)} /> 위 내용을 확인했습니다</label>
            <button type="submit" className="danger-btn" disabled={!agree || !password || busy}>{busy ? '처리 중…' : '탈퇴하기'}</button>
          </form>
          {error && <p className="form-error" role="alert">{error}</p>}
        </section>
      )}
    </div>
  )
}
