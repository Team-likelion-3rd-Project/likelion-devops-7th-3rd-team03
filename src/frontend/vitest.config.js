import { defineConfig } from 'vitest/config'

// 단위 테스트는 화면이 아니라 규칙(권한, 팀·링크 mock)을 검사한다. 브라우저 저장소는 setup 파일이 대신 만든다.
export default defineConfig({
  test: {
    environment: 'node',
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.js']
  }
})
