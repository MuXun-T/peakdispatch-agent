import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 4173,
    proxy: {
      '/api': process.env.PD_API_TARGET || 'http://localhost:8080',
      '/actuator': process.env.PD_API_TARGET || 'http://localhost:8080'
    }
  }
});
