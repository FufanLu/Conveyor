import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

// Dev: the app calls /api/... on its own origin and Vite forwards to the gateway, so the
// browser never needs CORS. Locally the gateway runs on 18080 (8080 is taken on this machine).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  return {
    plugins: [react()],
    server: {
      port: Number(env.PORT ?? 5280), // 5173 and 5174 are taken by other projects on this machine
      strictPort: true,
      proxy: { "/api": { target: env.CONVEYOR_GATEWAY ?? "http://localhost:18080", changeOrigin: false } },
    },
    // `vite preview` serves the production build with the same /api proxy (the end-to-end run).
    preview: {
      port: Number(env.PORT ?? 5280),
      strictPort: true,
      proxy: { "/api": { target: env.CONVEYOR_GATEWAY ?? "http://localhost:18080", changeOrigin: false } },
    },
    build: { target: "es2022", sourcemap: true },
  };
});
