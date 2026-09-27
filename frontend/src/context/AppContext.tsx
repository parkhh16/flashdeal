import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, setSessionInvalidHandler, setToken, type Session } from '../api'

const STORAGE_KEY = 'flashdeal.session'

interface Toast { id: number; kind: 'success' | 'error' | 'info'; message: string }

interface AppContextValue {
  session: Session | null
  /** 실패하면 ApiError를 던진다. 로그인 화면이 메시지를 폼 아래에 보여주기 위해서다 */
  login: (loginId: string, password: string) => Promise<Session>
  signup: (body: { loginId: string; password: string; name: string; email: string }) => Promise<Session>
  logout: () => void
  toast: (message: string, kind?: Toast['kind']) => void
  /** 에러를 사용자에게 서버 메시지로 알린다. 기록은 서버의 활동 로그가 담당한다 */
  fail: (e: unknown) => void
  ordersVersion: number
  bumpOrders: () => void
}

const AppContext = createContext<AppContextValue | null>(null)

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function saveSession(session: Session | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_KEY)
  } catch { /* 저장소를 못 쓰는 환경이면 새로고침 시 로그아웃될 뿐이다 */ }
}

export function AppProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [session, setSession] = useState<Session | null>(() => {
    const s = loadSession()
    setToken(s?.accessToken ?? null)
    return s
  })
  const [toasts, setToasts] = useState<Toast[]>([])
  const [ordersVersion, setOrdersVersion] = useState(0)
  const seq = useRef(0)

  const toast = useCallback((message: string, kind: Toast['kind'] = 'info') => {
    const id = ++seq.current
    setToasts(prev => [...prev.slice(-3), { id, kind, message }])
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 4000)
  }, [])

  const fail = useCallback((e: unknown) => {
    toast(e instanceof ApiError ? e.message : '알 수 없는 오류가 발생했습니다.', 'error')
  }, [toast])

  const applySession = useCallback((s: Session | null) => {
    setToken(s?.accessToken ?? null)
    setSession(s)
    saveSession(s)
  }, [])

  const login = useCallback(async (loginId: string, password: string) => {
    const { data } = await api.login(loginId, password)
    applySession(data)
    return data
  }, [applySession])

  const signup = useCallback(async (body: { loginId: string; password: string; name: string; email: string }) => {
    const { data } = await api.signup(body)
    applySession(data)
    return data
  }, [applySession])

  const logout = useCallback(() => applySession(null), [applySession])

  // 탈퇴·정지·강제 로그아웃·만료로 토큰이 무효가 되면, 어느 화면에서든 로그아웃 후 로그인 화면으로 보낸다
  useEffect(() => {
    setSessionInvalidHandler(message => {
      applySession(null)
      toast(message, 'error')
      navigate(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`)
    })
    return () => setSessionInvalidHandler(null)
  }, [applySession, toast, navigate])

  // 앱을 열 때 저장된 토큰이 아직 유효한지 확인한다
  useEffect(() => {
    if (session) api.me().catch(() => undefined)
  }, [])

  const value = useMemo<AppContextValue>(() => ({
    session, login, signup, logout, toast, fail,
    ordersVersion, bumpOrders: () => setOrdersVersion(v => v + 1),
  }), [session, login, signup, logout, toast, fail, ordersVersion])

  return (
    <AppContext.Provider value={value}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(t => <div key={t.id} className={`toast ${t.kind}`}>{t.message}</div>)}
      </div>
    </AppContext.Provider>
  )
}

export function useApp() {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}
