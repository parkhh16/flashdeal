import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버에서 /api 요청을 백엔드(8080)로 프록시해서 CORS 설정 없이 동작하게 한다
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
