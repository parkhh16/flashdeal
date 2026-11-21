import type { ReactNode } from 'react'

/** 인라인 SVG 아이콘. 24x24 viewBox, currentColor 선, 굵기 1.75, 장식용이라 aria-hidden */
function Icon({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {children}
    </svg>
  )
}

export const BoltIcon = ({ className }: { className?: string }) => (
  <Icon className={className}><path d="M13 3 5 13.5h6L10 21l8-10.5h-6L13 3Z" /></Icon>
)

export const SearchIcon = ({ className }: { className?: string }) => (
  <Icon className={className}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></Icon>
)

export const CloseIcon = ({ className }: { className?: string }) => (
  <Icon className={className}><path d="M6 6l12 12M18 6 6 18" /></Icon>
)

/** 로고: accent 면의 라운드 사각형 + 번개 */
export function LogoMark() {
  return <span className="logo-mark"><BoltIcon /></span>
}
