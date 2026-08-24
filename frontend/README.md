# LedgerPay Demo Console

React, Vite, and TypeScript frontend for the LedgerPay sandbox demo.

## Local development

Start the Spring Boot backend on `http://localhost:8080`. The frontend creates a
new Demo Merchant automatically when the current browser tab has no demo
credential. The existing bootstrap script remains available when you want to
pre-create a local Merchant and write its API key and Webhook URL to
`frontend/.env.local`:

```bash
./scripts/bootstrap-demo.sh
```

Then start the frontend:

```bash
npm install
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`). The Vite
development server proxies `/health` and `/api` to the backend so the UI can use
the real API without changing backend CORS configuration. Vite exposes the
optional local demo configuration to the frontend at runtime. The active API
key is stored in the browser tab's `sessionStorage`, is not included in the
production bundle, and is never shown in full in the UI or API Response
Console.

To receive local Webhooks, run this in another terminal before completing a
Payment or Refund:

```bash
python3 scripts/mock_webhook_receiver.py --status 204
```

Use `--status 500` to exercise automatic failure and retry behavior. API-key
rotation updates the current tab's stored credential. **Reset Demo** creates a
new Merchant and replaces it. To switch the current tab back to a Merchant
written by `./scripts/bootstrap-demo.sh`, clear the `ledgerpay.demo.apiKey`
session-storage entry (or open a new tab) and restart Vite.

To use a different backend address:

```bash
VITE_API_TARGET=http://localhost:8081 npm run dev
```

For a production static build, `VITE_LEDGERPAY_WEBHOOK_URL` may provide the
public, non-secret Webhook destination stored on each automatically created
Demo Merchant. Production startup does not load an API key from a frontend env
file or from `/__ledgerpay-demo-config`.

## Checks

```bash
npm run lint
npm run build
```
