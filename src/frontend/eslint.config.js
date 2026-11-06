import js from '@eslint/js'
import globals from 'globals'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import prettier from 'eslint-config-prettier'

export default [
  { ignores: ['dist', 'node_modules'] },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } }
    },
    settings: { react: { version: 'detect' } },
    plugins: { react, 'react-hooks': reactHooks },
    rules: {
      ...react.configs.recommended.rules,
      ...react.configs['jsx-runtime'].rules,
      // PropTypes를 쓰지 않는 프로젝트라 끈다.
      'react/prop-types': 'off',
      // hooks는 기본 두 규칙만 쓴다. (React Compiler용 규칙은 켜지 않는다)
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn'
    }
  },
  {
    // vite.config.js는 브라우저가 아니라 Node에서 실행된다.
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: { globals: globals.node }
  },
  // 포맷 규칙은 Prettier가 맡는다. 충돌하는 ESLint 규칙을 끈다. (맨 뒤에 둔다)
  prettier
]
