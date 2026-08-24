import type { IncomingMessage, ServerResponse } from 'node:http'
import { defineConfig, loadEnv, type Plugin, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

const DEMO_CONFIG_PATH = '/__ledgerpay-demo-config'

function demoConfigPlugin(apiKey: string | undefined, webhookUrl: string): Plugin {
  const middleware = (_request: IncomingMessage, response: ServerResponse) => {
    const body = JSON.stringify({ apiKey: apiKey || null, webhookUrl })
    response.statusCode = 200
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Cache-Control', 'no-store')
    response.end(body)
  }

  return {
    name: 'ledgerpay-demo-config',
    configureServer(server) {
      server.middlewares.use(DEMO_CONFIG_PATH, middleware)
    },
    configurePreviewServer(server) {
      server.middlewares.use(DEMO_CONFIG_PATH, middleware)
    },
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'
  const apiKey = env.LEDGERPAY_DEMO_API_KEY
  const webhookUrl = env.LEDGERPAY_WEBHOOK_URL || 'http://localhost:9000/webhook'

  const healthProxy: ProxyOptions = {
    target: apiTarget,
    changeOrigin: true,
  }

  const apiProxy: ProxyOptions = {
    target: apiTarget,
    changeOrigin: true,
  }

  const proxy = {
    '/health': healthProxy,
    '/api': apiProxy,
  }

  return {
    plugins: [react(), demoConfigPlugin(apiKey, webhookUrl)],
    server: { proxy },
    preview: { proxy },
  }
})
