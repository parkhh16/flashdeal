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

type IconProps = { className?: string }

export const GridIcon = ({ className }: IconProps) => (
  <Icon className={className}><rect x="4" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" /></Icon>
)
export const KeyboardIcon = ({ className }: IconProps) => (
  <Icon className={className}><rect x="2.5" y="6" width="19" height="12" rx="2.5" /><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M7.5 14h9" /></Icon>
)
export const MouseIcon = ({ className }: IconProps) => (
  <Icon className={className}><rect x="6" y="3" width="12" height="18" rx="6" /><path d="M12 7v4" /></Icon>
)
export const MonitorIcon = ({ className }: IconProps) => (
  <Icon className={className}><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></Icon>
)
export const HeadphoneIcon = ({ className }: IconProps) => (
  <Icon className={className}><path d="M4 15v-3a8 8 0 0 1 16 0v3" /><rect x="3" y="14" width="4" height="6.5" rx="1.5" /><rect x="17" y="14" width="4" height="6.5" rx="1.5" /></Icon>
)
export const AccessoryIcon = ({ className }: IconProps) => (
  <Icon className={className}><path d="M9 3v5M15 3v5M7 8h10v3a5 5 0 0 1-10 0V8ZM12 16v5" /></Icon>
)
export const BoxIcon = ({ className }: IconProps) => (
  <Icon className={className}><path d="M21 8 12 3 3 8v8l9 5 9-5V8ZM3 8l9 5 9-5M12 13v8" /></Icon>
)
/** 빈 결과용 돋보기(안에 빼기 표시) */
export const EmptySearchIcon = ({ className }: IconProps) => (
  <Icon className={className}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2M8.5 11h5" /></Icon>
)

/** 카테고리 코드 → 선 아이콘. 모르는 코드는 상자 */
export function CategoryIcon({ code, className }: { code: string | null | undefined; className?: string }) {
  switch (code) {
    case 'KEYBOARD': return <KeyboardIcon className={className} />
    case 'MOUSE': return <MouseIcon className={className} />
    case 'MONITOR': return <MonitorIcon className={className} />
    case 'AUDIO': return <HeadphoneIcon className={className} />
    case 'ACCESSORY': return <AccessoryIcon className={className} />
    default: return <BoxIcon className={className} />
  }
}

/** 로고: accent 면의 라운드 사각형 + 번개 */
export function LogoMark() {
  return <span className="logo-mark"><BoltIcon /></span>
}
