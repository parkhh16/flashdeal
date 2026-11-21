/**
 * 니즈샵 로고 도형 (둥근 안경). 24x24 좌표, 선(stroke)으로만 그린다.
 * 로고를 바꿀 때는 이 파일만 고친다:
 *  - 헤더, 로그인, 가입 화면의 LogoMark (components/icons.tsx)가 이 도형을 그린다
 *  - 브라우저 탭 아이콘 public/favicon.svg는 `npm run logo`가 이 도형으로 다시 만든다 (scripts/gen-favicon.mjs)
 */
/** 헤더 로고의 선 굵기. 안경은 얇은 선이 작은 크기에서 "oo"로 뭉치므로 다른 아이콘(1.75)보다 조금 굵게 한다 */
export const LOGO_STROKE = 2.1
export const LOGO_INNER =
  `<circle cx="7.4" cy="12.5" r="3.9"/>` +
  `<circle cx="16.6" cy="12.5" r="3.9"/>` +
  `<path d="M11.3 12c.4-.6 1.6-.6 2 0"/>` +
  `<path d="M3.6 11.6 2.6 9.4"/>` +
  `<path d="M20.4 11.6l1-2.2"/>`
