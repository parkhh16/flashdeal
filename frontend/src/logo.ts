/**
 * 니즈샵 로고 도형 (모노클 + 체크). 24x24 좌표, 선(stroke)으로만 그린다.
 * 로고를 바꿀 때는 이 파일만 고친다:
 *  - 헤더, 로그인, 가입 화면의 LogoMark (components/icons.tsx)가 이 도형을 그린다
 *  - 브라우저 탭 아이콘 public/favicon.svg는 `npm run logo`가 이 도형으로 다시 만든다 (scripts/gen-favicon.mjs)
 */
export const LOGO_INNER =
  `<circle cx="11" cy="10" r="6.5"/>` +
  `<path d="m8.2 10.2 2 2 3.6-3.9"/>` +
  `<path d="M17.5 10.4c2.5 1.1 3.5 3.8 2.7 6.4-.4 1.4-.3 2.5.2 3.8" stroke-dasharray="0 2.6"/>`
