import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useApp } from '../context/AppContext'
import { ChevronDownIcon, CloseIcon, LogoMark, SearchIcon } from './icons'

/** 헤더 검색. 제출 동작은 기존 SearchBox와 같다: 값이 있으면 /?q=..., 없으면 / */
function HeaderSearch() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const current = params.get('q')
  const [value, setValue] = useState(current ?? '')
  useEffect(() => setValue(current ?? ''), [current])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const q = value.trim()
    navigate(q ? `/?q=${encodeURIComponent(q)}` : '/')
  }

  return (
    <form className="header-search" onSubmit={submit} role="search">
      <div className="hs-field">
        <SearchIcon className="hs-icon" />
        <input value={value} onChange={e => setValue(e.target.value)} aria-label="상품 검색"
               placeholder="예: 5만원 이하 무선 마우스" />
        {value && <button type="button" className="hs-clear" onClick={() => { setValue(''); navigate('/') }} aria-label="검색어 지우기"><CloseIcon /></button>}
        <button type="submit" className="hs-submit" aria-label="검색"><SearchIcon /></button>
      </div>
    </form>
  )
}

export function Header() {
  const { session, logout, toast } = useApp()
  const [menuOpen, setMenuOpen] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => setMenuOpen(false), [location.pathname])
  // 메뉴 바깥을 누르면 닫는다
  useEffect(() => {
    if (!menuOpen) return
    const close = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [menuOpen])

  const doLogout = () => {
    logout()
    toast('로그아웃했습니다.')
    navigate('/')
  }

  const next = encodeURIComponent(location.pathname + location.search)

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="brand"><LogoMark />니즈샵</Link>
        {!location.pathname.startsWith('/admin') && <HeaderSearch />}
        <nav className="header-nav">
          {session?.role === 'ADMIN' && <NavLink to="/admin" className="pc-only">관리자</NavLink>}
          {session && <NavLink to="/me" end className="pc-only">내 활동</NavLink>}
          {!session && (
            <>
              <Link to={`/login?next=${next}`} className="header-link">로그인</Link>
              <Link to={`/signup?next=${next}`} className="header-cta">회원가입</Link>
            </>
          )}
          {session && (
            <div className="account" ref={menuRef}>
              {/* 아바타만 있으면 메뉴 버튼인지 알 수 없다. 좁은 창에서는 "내 활동·관리자"가 이 메뉴 안에만 있어서 ▾를 붙인다 */}
              <button className="account-trigger" onClick={() => setMenuOpen(o => !o)} aria-expanded={menuOpen} aria-haspopup="menu" aria-label={`${session.name}님 계정 메뉴`}>
                <span className="avatar" aria-hidden="true">{Array.from(session.name)[0] ?? '?'}</span>
                <ChevronDownIcon className="account-chevron" />
              </button>
              {menuOpen && (
                <div className="account-menu" role="menu">
                  <div className="menu-title"><strong>{session.name}님</strong>{session.loginId}</div>
                  <Link role="menuitem" to="/me">내 활동</Link>
                  <Link role="menuitem" to="/me/account">계정 설정</Link>
                  {session.role === 'ADMIN' && <Link role="menuitem" to="/admin">관리자</Link>}
                  <button role="menuitem" className="logout" onClick={doLogout}>로그아웃</button>
                </div>
              )}
            </div>
          )}
        </nav>
      </div>
    </header>
  )
}
