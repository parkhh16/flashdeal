import { NavLink, Outlet, useLocation } from 'react-router-dom'

export const ADMIN_SECTIONS = [
  {
    to: '/admin', label: '대시보드', title: '운영 대시보드',
    desc: '최근 24시간 동안 서비스가 건강한지 한눈에 봅니다. 요청 수, 주문 수, 서버 에러율, 평균 응답 속도를 확인하세요.',
  },
  {
    to: '/admin/activity', label: '활동 로그', title: '활동 로그',
    desc: '누가, 언제, 무엇을 했고 결과가 어땠는지 서버에 저장된 기록을 조회합니다. 사용자를 고르면 그 사람의 타임라인을 볼 수 있어요. CS 문의가 오면 traceId로 해당 요청을 바로 찾을 수 있습니다.',
  },
  {
    to: '/admin/users', label: '회원 관리', title: '회원 관리',
    desc: '가입한 회원을 조회하고 정지·정지 해제, 강제 로그아웃, 로그인 잠금 해제를 합니다. 정지하거나 강제 로그아웃하면 이미 로그인해 있던 세션도 즉시 끊깁니다. 탈퇴 회원은 개인정보가 파기된 상태로 표시됩니다.',
  },
  {
    to: '/admin/products', label: '상품 관리', title: '상품 · 특가 관리',
    desc: '상품을 등록·수정하고 특가 조건(가격, 오픈/종료 시각, 한정 수량, 1인 구매 제한)을 설정합니다. 판매 중지하면 쇼핑몰에서 바로 숨겨집니다.',
  },
  {
    to: '/admin/orders', label: '주문 관리', title: '주문 관리',
    desc: '전체 주문을 상태별로 조회하고 취소합니다. 결제 대기 주문은 즉시 취소되고, 결제 완료 주문은 PG 환불이 성공한 경우에만 취소됩니다. 취소하면 재고가 복구됩니다. 정합성 점검으로 결제 완료 주문과 승인 금액이 맞는지 확인할 수 있습니다.',
  },
]

export function AdminLayout() {
  const { pathname } = useLocation()
  const current = [...ADMIN_SECTIONS].reverse().find(s => pathname === s.to || pathname.startsWith(s.to + '/')) ?? ADMIN_SECTIONS[0]
  return (
    <div className="admin-page">
      <nav className="admin-tabs" aria-label="관리자 메뉴">
        {ADMIN_SECTIONS.map(s => <NavLink key={s.to} to={s.to} end={s.to === '/admin'}>{s.label}</NavLink>)}
      </nav>
      <header className="admin-intro">
        <h2>{current.title}</h2>
        <p>{current.desc}</p>
      </header>
      <Outlet />
    </div>
  )
}
