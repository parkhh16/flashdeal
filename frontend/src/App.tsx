import { useEffect, useState, type ReactNode } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { Header } from './components/Header'
import { Sidebar } from './components/Sidebar'
import { AppProvider, useApp } from './context/AppContext'
import { AdminActivityPage } from './pages/admin/AdminActivityPage'
import { AdminDashboard } from './pages/admin/AdminDashboard'
import { AdminLayout } from './pages/admin/AdminLayout'
import { AdminOrdersPage } from './pages/admin/AdminOrdersPage'
import { AdminProductsPage } from './pages/admin/AdminProductsPage'
import { AdminUsersPage } from './pages/admin/AdminUsersPage'
import { AccountPage } from './pages/auth/AccountPage'
import { LoginPage } from './pages/auth/LoginPage'
import { SignupPage } from './pages/auth/SignupPage'
import { MyActivityPage } from './pages/MyActivityPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { ShopPage } from './pages/ShopPage'

/**
 * 로그인이 필요한 화면. 로그인하지 않았으면 로그인 화면으로 보내고, 로그인 후 원래 화면으로 돌아온다.
 * 이건 UX일 뿐이고 실제 권한은 서버가 검사한다 (/api/admin/** → ROLE_ADMIN, 본인 데이터만 조회)
 */
function RequireAuth({ admin = false, children }: { admin?: boolean; children: ReactNode }) {
  const { session } = useApp()
  const location = useLocation()
  if (!session) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  if (admin && session.role !== 'ADMIN') return <Navigate to="/" replace />
  return <>{children}</>
}

function Layout() {
  const [drawer, setDrawer] = useState(false)
  const location = useLocation()
  const fullWidth = location.pathname.startsWith('/admin') || ['/login', '/signup'].includes(location.pathname)

  // 페이지를 이동하면 모바일 드로어를 닫고 맨 위로 스크롤한다
  useEffect(() => {
    setDrawer(false)
    window.scrollTo(0, 0)
  }, [location.pathname, location.search])

  return (
    <div className="app">
      <Header onMenu={() => setDrawer(true)} showMenu={!fullWidth} />
      <div className={`body ${fullWidth ? 'wide' : ''}`}>
        {!fullWidth && <Sidebar open={drawer} onClose={() => setDrawer(false)} />}
        <main className="main">
          <Routes>
            <Route path="/" element={<ShopPage />} />
            <Route path="/products/:id" element={<ProductDetailPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/me" element={<RequireAuth><MyActivityPage /></RequireAuth>} />
            <Route path="/me/account" element={<RequireAuth><AccountPage /></RequireAuth>} />
            <Route path="/orders" element={<Navigate to="/me" replace />} />
            <Route path="/admin" element={<RequireAuth admin><AdminLayout /></RequireAuth>}>
              <Route index element={<AdminDashboard />} />
              <Route path="activity" element={<AdminActivityPage />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="products" element={<AdminProductsPage />} />
              <Route path="orders" element={<AdminOrdersPage />} />
            </Route>
            <Route path="*" element={<div className="empty"><p>페이지를 찾을 수 없습니다.</p><Link to="/" className="ghost-link">홈으로</Link></div>} />
          </Routes>
        </main>
      </div>
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AppProvider>
        <Layout />
      </AppProvider>
    </BrowserRouter>
  )
}
