import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// In dev, `npm run dev` serves the UI on :5173 and forwards /api to the Kotlin server on :8080,
// so the browser sees a single origin (the session cookie then works without CORS).
// In production Caddy does the same job: static files from dist/, /api proxied to the backend.
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } },
  },
})
