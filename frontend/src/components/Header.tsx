import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useApp } from '../context/AppContext'

export function Header({ onMenu, showMenu = true }: { onMenu: () => void; showMenu?: boolean }) {
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
        {showMenu && <button className="icon-btn only-mobile" onClick={onMenu} aria-label="카테고리 열기">☰</button>}
        <Link to="/" className="brand"><span className="bolt">⚡</span>FlashDeal</Link>
        <nav className="header-nav">
          {session?.role === 'ADMIN' && <NavLink to="/admin">관리자</NavLink>}
          {session && <NavLink to="/me" end>내 활동</NavLink>}
          {!session && (
            <>
              <Link to={`/login?next=${next}`} className="header-link">로그인</Link>
              <Link to={`/signup?next=${next}`} className="header-cta">회원가입</Link>
            </>
          )}
          {session && (
            <div className="account" ref={menuRef}>
              <button className="ghost" onClick={() => setMenuOpen(o => !o)} aria-expanded={menuOpen} aria-haspopup="menu">
                {session.name}님{session.role === 'ADMIN' && <span className="role admin">ADMIN</span>} ▾
              </button>
              {menuOpen && (
                <div className="account-menu" role="menu">
                  <div className="menu-title">{session.loginId}</div>
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
