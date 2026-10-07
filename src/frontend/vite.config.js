import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
    proxy: {
      // 로컬은 localhost, Compose에서는 VITE_API_PROXY_TARGET으로 서비스명을 주입한다.
      '/api': {
        // Compose 컨테이너에서는 localhost가 아니라 서비스명으로 접근해야 한다.
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
