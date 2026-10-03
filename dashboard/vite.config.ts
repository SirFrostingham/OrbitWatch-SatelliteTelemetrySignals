import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Build output goes straight into the gateway's static resources so the Spring Boot jar serves it.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../gateway-service/src/main/resources/static', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080', '/graphql': 'http://localhost:8080' },
  },
})
