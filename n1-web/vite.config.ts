/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // Same-origin /api in dev, so EventSource and fetch don't need CORS.
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom' },
})
