import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    exclude: ['**/node_modules/**', '**/tests/**', '**/apiTokenGuard.test.js'],
    setupFiles: ['./src/test/setup.js'],
  },
})
