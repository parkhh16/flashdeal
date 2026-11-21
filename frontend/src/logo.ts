/**
 * 니즈샵 로고 도형 (돋보기 + 체크). 24x24 좌표, 선(stroke)으로만 그린다.
 * 로고를 바꿀 때는 이 파일만 고친다:
 *  - 헤더, 로그인, 가입 화면의 LogoMark (components/icons.tsx)가 이 도형을 그린다
 *  - 브라우저 탭 아이콘 public/favicon.svg는 `npm run logo`가 이 도형으로 다시 만든다 (scripts/gen-favicon.mjs)
 */
export const LOGO_INNER =
  `<circle cx="11" cy="11" r="6.5"/>` +
  `<path d="m20 20-4.2-4.2"/>` +
  `<path d="m8.4 11 1.8 1.8 3.4-3.6"/>`
