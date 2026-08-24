# LedgerPay Demo Console

React, Vite, and TypeScript frontend for the LedgerPay sandbox demo.

## Local development

Start the Spring Boot backend on `http://localhost:8080`. From the repository
root, create a unique local demo Merchant and write its API key and Webhook URL
to `frontend/.env.local`:

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
local demo configuration to the frontend at runtime. The API key is held only
in the current page's memory after it is loaded from `.env.local`, is not
included in the production bundle, and is never shown in full in the UI or API
Response Console.

To receive local Webhooks, run this in another terminal before completing a
Payment or Refund:

```bash
python3 scripts/mock_webhook_receiver.py --status 204
```

Use `--status 500` to exercise automatic failure and retry behavior. After API
key rotation, restart the demo from the browser with **Reset Demo**, or rerun
`./scripts/bootstrap-demo.sh` and restart Vite to refresh `.env.local`.

To use a different backend address:

```bash
VITE_API_TARGET=http://localhost:8081 npm run dev
```

## Checks

```bash
npm run lint
npm run build
```
