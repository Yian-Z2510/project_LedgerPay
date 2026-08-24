import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  BellOff,
  Check,
  CircleEllipsis,
  CircleX,
  Copy,
  Info,
  RefreshCw,
  ShoppingCart,
  Store,
  Webhook,
} from 'lucide-react'
import { apiRequest, ApiRequestError, setActiveApiKey, type ApiResult } from './api'
import { generateUuid } from './uuid'
import type {
  ApiConsoleEntry,
  CreateMerchantResponse,
  Merchant,
  Order,
  Payment,
  PaymentFailureCode,
  Refund,
  RefundFailureCode,
  RefundReasonCode,
  RotateApiKeyResponse,
  WebhookEvent,
} from './types'
import './App.css'

type HealthState = 'checking' | 'healthy' | 'unavailable'
type DemoConfig = {
  apiKey: string | null
  webhookUrl: string
}
const DEMO_API_KEY_STORAGE_KEY = 'ledgerpay.demo.apiKey'
const PRODUCTION_WEBHOOK_URL =
  import.meta.env.VITE_LEDGERPAY_WEBHOOK_URL?.trim() || null
let initialDemoMerchantRequest: Promise<ApiResult<CreateMerchantResponse>> | null = null

type RefundReplayRequest = {
  paymentId: string
  amount: number
  reasonCode: RefundReasonCode
  idempotencyKey: string
}
type BusyAction =
  | 'create-order'
  | 'update-order'
  | 'cancel-order'
  | 'create-payment'
  | 'retry-payment'
  | 'simulate-success'
  | 'simulate-failure'
  | 'create-refund'
  | 'retry-refund'
  | 'simulate-refund-success'
  | 'simulate-refund-failure'
  | 'refresh-webhooks'
  | 'select-webhook'
  | 'retry-webhook'
  | 'rotate-api-key'
  | 'deactivate-merchant'
  | 'reset-demo'

const READY_CONSOLE: ApiConsoleEntry = {
  method: 'READY',
  endpoint: 'Waiting for request…',
  status: null,
  statusText: 'WAITING',
  state: 'ready',
}

function formatMoney(amount: number) {
  return new Intl.NumberFormat('en-IE', {
    style: 'currency',
    currency: 'EUR',
  }).format(amount / 100)
}

function amountToInput(amount: number) {
  return (amount / 100).toFixed(2)
}

function formatTimestamp(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('en-IE', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function webhookResourceId(event: WebhookEvent) {
  const payment = isRecord(event.data.payment) ? event.data.payment : null
  const refund = isRecord(event.data.refund) ? event.data.refund : null
  const id = refund?.id ?? payment?.id
  return typeof id === 'string' ? id : '—'
}

function parseMinorUnits(value: string): number | null {
  const match = value.trim().match(/^(-?\d+)(?:\.(\d{1,2}))?$/)
  if (!match) return null

  const negative = match[1].startsWith('-')
  const whole = Math.abs(Number(match[1]))
  const fraction = Number((match[2] ?? '').padEnd(2, '0'))
  const unsignedAmount = whole * 100 + fraction
  const amount = negative ? -unsignedAmount : unsignedAmount
  return Number.isSafeInteger(amount) ? amount : null
}

function createPaymentIdempotencyKey() {
  return `payment_${generateUuid()}`
}

function createRefundIdempotencyKey() {
  return `refund_${generateUuid()}`
}

function maskApiKey(apiKey: string | null) {
  return apiKey ? `lp_test_••••••${apiKey.slice(-4)}` : 'unavailable'
}

function merchantFromCreation(response: CreateMerchantResponse): Merchant {
  return {
    id: response.id,
    name: response.name,
    email: response.email,
    status: response.status,
    webhookUrl: response.webhookUrl,
    deactivatedAt: response.deactivatedAt,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
}

function redactedKeyResponse<T extends { apiKey: string }>(response: T) {
  return { ...response, apiKey: maskApiKey(response.apiKey) }
}

function createDemoMerchantBody(webhookUrl: string | null) {
  return {
    name: 'LedgerPay Demo',
    email: `ledgerpay-demo-${Date.now()}-${generateUuid()}@example.com`,
    webhookUrl,
  }
}

function createInitialDemoMerchant(webhookUrl: string | null) {
  initialDemoMerchantRequest ??= apiRequest<CreateMerchantResponse>(
    '/api/v1/merchants',
    {
      method: 'POST',
      body: JSON.stringify(createDemoMerchantBody(webhookUrl)),
    },
    { authenticated: false },
  )
  return initialDemoMerchantRequest
}

const REFUND_REASONS: Array<{ value: RefundReasonCode; label: string }> = [
  { value: 'CUSTOMER_REQUEST', label: 'Customer request' },
  { value: 'DUPLICATE_CHARGE', label: 'Duplicate charge' },
  { value: 'PRODUCT_NOT_RECEIVED', label: 'Product not received' },
  { value: 'OTHER', label: 'Other' },
]

function errorMessage(error: unknown) {
  if (error instanceof ApiRequestError) {
    return error.message
  }
  return 'An unexpected frontend error occurred.'
}

function HealthIndicator() {
  const [health, setHealth] = useState<HealthState>('checking')

  useEffect(() => {
    const controller = new AbortController()

    async function checkHealth() {
      try {
        const response = await fetch('/health', { signal: controller.signal })
        const body = (await response.json()) as { status?: string }
        setHealth(response.ok && body.status === 'UP' ? 'healthy' : 'unavailable')
      } catch (error) {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setHealth('unavailable')
        }
      }
    }

    void checkHealth()
    return () => controller.abort()
  }, [])

  const label = {
    checking: 'Checking API',
    healthy: 'API Healthy',
    unavailable: 'API Unavailable',
  }[health]

  return (
    <div className={`health health--${health}`} role="status" aria-live="polite">
      <span className="health__dot" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

type HeaderProps = {
  busy: BusyAction | null
  onReset: () => void
}

function Header({ busy, onReset }: HeaderProps) {
  return (
    <header className="topbar">
      <div className="topbar__content">
        <h1>LedgerPay | Demo Console</h1>
        <div className="topbar__actions">
          <HealthIndicator />
          <button
            className="button button--secondary"
            type="button"
            onClick={onReset}
            disabled={busy !== null}
          >
            {busy === 'reset-demo' ? 'Resetting…' : 'Reset Demo'}
          </button>
        </div>
      </div>
    </header>
  )
}

type MerchantCardProps = {
  merchant: Merchant | null
  apiKey: string | null
  busy: BusyAction | null
  onRotate: () => void
  onDeactivate: () => void
}

function MerchantCard({
  merchant,
  apiKey,
  busy,
  onRotate,
  onDeactivate,
}: MerchantCardProps) {
  const [copiedApiKey, setCopiedApiKey] = useState<string | null>(null)
  const copyTimer = useRef<number | null>(null)
  const merchantActive = merchant?.status === 'ACTIVE'
  const statusLabel = merchant?.status === 'INACTIVE' ? 'DEACTIVATED' : merchant?.status ?? 'LOADING'

  useEffect(() => () => {
    if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
  }, [])

  async function copyApiKey() {
    if (!apiKey) return
    try {
      await navigator.clipboard.writeText(apiKey)
      setCopiedApiKey(apiKey)
      if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
      copyTimer.current = window.setTimeout(() => {
        setCopiedApiKey(null)
        copyTimer.current = null
      }, 1400)
    } catch {
      setCopiedApiKey(null)
    }
  }

  return (
    <section
      className={`merchant-card${merchant?.status === 'INACTIVE' ? ' merchant-card--inactive' : ''}`}
      aria-labelledby="merchant-heading"
    >
      <div className="merchant-card__main">
        <div className="merchant-card__identity">
          <div className="merchant-card__icon" aria-hidden="true">
            <Store size={24} strokeWidth={2.25} />
          </div>
          <div>
            <h2 id="merchant-heading">Merchant: {merchant?.name ?? 'Demo Store'}</h2>
            <div className="merchant-card__metadata">
              <span>ID: {merchant?.id ?? 'loading…'}</span>
              <span className="separator" aria-hidden="true">•</span>
              <span className={`status-badge${merchant?.status === 'INACTIVE' ? ' status-badge--inactive' : ''}`}>
                {statusLabel}
              </span>
              <span className="separator" aria-hidden="true">•</span>
              <span className="api-key">API Key: {maskApiKey(apiKey)}</span>
              <button
                className="icon-button merchant-copy"
                type="button"
                aria-label="Copy full API key"
                onClick={() => { void copyApiKey() }}
                disabled={!apiKey}
              >
                <Copy size={17} />
                <span aria-live="polite">{copiedApiKey === apiKey ? 'Copied' : ''}</span>
              </button>
            </div>
          </div>
        </div>
        <div className="merchant-card__actions">
          <button
            className="button button--secondary"
            type="button"
            onClick={onRotate}
            disabled={!merchantActive || busy !== null}
          >
            {busy === 'rotate-api-key' ? 'Regenerating…' : 'Regenerate API Key'}
          </button>
          <button
            className="button button--danger"
            type="button"
            onClick={onDeactivate}
            disabled={!merchantActive || busy !== null}
          >
            {busy === 'deactivate-merchant' ? 'Deactivating…' : 'Deactivate Merchant'}
          </button>
        </div>
      </div>
      {merchant?.status === 'INACTIVE' && (
        <div className="merchant-deactivated" role="status">
          <Info size={19} aria-hidden="true" />
          <span>Merchant is deactivated. Reset the demo to continue.</span>
        </div>
      )}
    </section>
  )
}

type WorkflowStepProps = {
  icon: ReactNode
  state: 'active' | 'inactive'
  children: ReactNode
}

function WorkflowStep({ icon, state, children }: WorkflowStepProps) {
  return (
    <div className={`workflow-step workflow-step--${state}`}>
      <div className="workflow-step__rail">
        <div className="workflow-step__icon" aria-hidden="true">{icon}</div>
      </div>
      <section className="workflow-card">{children}</section>
    </div>
  )
}

function StatusBadge({ status }: { status: string }) {
  const tone = status === 'FAILED' || status === 'CANCELLED'
    ? 'error'
    : status === 'PENDING' || status === 'PAYMENT_PENDING'
      ? 'pending'
      : 'success'

  return <span className={`resource-status resource-status--${tone}`}>{status}</span>
}

type OrderSectionProps = {
  order: Order | null
  payment: Payment | null
  amountInput: string
  editing: boolean
  busy: BusyAction | null
  merchantReady: boolean
  onAmountChange: (value: string) => void
  onCreate: () => void
  onStartEditing: () => void
  onStopEditing: () => void
  onUpdate: () => void
  onCancel: () => void
}

function OrderSection({
  order,
  payment,
  amountInput,
  editing,
  busy,
  merchantReady,
  onAmountChange,
  onCreate,
  onStartEditing,
  onStopEditing,
  onUpdate,
  onCancel,
}: OrderSectionProps) {
  const requestRunning = busy !== null || !merchantReady

  if (!order) {
    return (
      <WorkflowStep state="active" icon={<ShoppingCart size={19} />}>
        <div className="order-card">
          <div className="order-card__form">
            <h2>Order</h2>
            <div className="amount-row">
              <label className="amount-input">
                <span className="sr-only">Order amount</span>
                <span aria-hidden="true">€</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={amountInput}
                  placeholder="Please enter order amount"
                  onChange={(event) => onAmountChange(event.target.value)}
                  disabled={requestRunning}
                />
              </label>
              <span className="currency">EUR</span>
            </div>
          </div>
          <button
            className="button button--primary create-order"
            type="button"
            onClick={onCreate}
            disabled={!merchantReady || requestRunning}
          >
            {busy === 'create-order' ? 'Creating…' : 'Create Order'}
          </button>
        </div>
      </WorkflowStep>
    )
  }

  const canEdit = order.status === 'CREATED' && payment === null
  const canCancel =
    order.status === 'CREATED' ||
    (order.status === 'PAYMENT_PENDING' && payment?.status !== 'PENDING')

  return (
    <WorkflowStep state="active" icon={<ShoppingCart size={19} />}>
      <div className="resource-card">
        <div className="resource-card__topline">
          <div>
            <div className="resource-heading">
              <h2>Order #{order.id}</h2>
              <StatusBadge status={order.status} />
            </div>
            {!editing && (
              <p>Amount: <strong>{formatMoney(order.amount)}</strong> {order.currency}</p>
            )}
          </div>
          <div className="resource-actions">
            {!editing ? (
              <button
                className="button button--secondary"
                type="button"
                onClick={onStartEditing}
                disabled={!canEdit || requestRunning}
              >
                Edit Amount
              </button>
            ) : (
              <button
                className="button button--primary"
                type="button"
                onClick={onUpdate}
                disabled={requestRunning}
              >
                {busy === 'update-order' ? 'Saving…' : 'Save Amount'}
              </button>
            )}
            {editing ? (
              <button
                className="button button--secondary"
                type="button"
                onClick={onStopEditing}
                disabled={requestRunning}
              >
                Cancel Edit
              </button>
            ) : (
              <button
                className="button button--danger"
                type="button"
                onClick={onCancel}
                disabled={!canCancel || requestRunning}
              >
                {busy === 'cancel-order' ? 'Cancelling…' : 'Cancel Order'}
              </button>
            )}
          </div>
        </div>
        {editing && (
          <div className="compact-edit-row">
            <label className="amount-input">
              <span className="sr-only">Updated order amount</span>
              <span aria-hidden="true">€</span>
              <input
                type="text"
                inputMode="decimal"
                value={amountInput}
                onChange={(event) => onAmountChange(event.target.value)}
                disabled={requestRunning}
                autoFocus
              />
            </label>
            <span className="currency">EUR</span>
          </div>
        )}
      </div>
    </WorkflowStep>
  )
}

type PaymentSectionProps = {
  order: Order | null
  payment: Payment | null
  idempotencyKey: string
  replayed: boolean
  busy: BusyAction | null
  merchantReady: boolean
  onCreate: () => void
  onRetry: () => void
  onSimulateSuccess: () => void
  onSimulateFailure: () => void
}

function PaymentSection({
  order,
  payment,
  idempotencyKey,
  replayed,
  busy,
  merchantReady,
  onCreate,
  onRetry,
  onSimulateSuccess,
  onSimulateFailure,
}: PaymentSectionProps) {
  const requestRunning = busy !== null || !merchantReady

  if (!order) {
    return (
      <WorkflowStep state="inactive" icon={<Check size={20} strokeWidth={2.5} />}>
        <h2>Payment</h2>
        <p>Create an order first</p>
      </WorkflowStep>
    )
  }

  if (order.status === 'CANCELLED' && !payment) {
    return (
      <WorkflowStep state="inactive" icon={<CircleX size={20} />}>
        <h2>Payment</h2>
        <p>The order is cancelled</p>
      </WorkflowStep>
    )
  }

  if (!payment) {
    return (
      <WorkflowStep state="active" icon={<Check size={20} strokeWidth={2.5} />}>
        <div className="payment-ready">
          <h2>Payment</h2>
          <div className="amount-summary">
            Order Amount: <strong>{formatMoney(order.amount)} {order.currency}</strong>
          </div>
          <div className="developer-metadata">
            <span>Idempotency Key</span>
            <code>{idempotencyKey}</code>
          </div>
          <button
            className="button button--primary payment-create"
            type="button"
            onClick={onCreate}
            disabled={requestRunning}
          >
            {busy === 'create-payment' ? 'Creating…' : 'Create Payment'}
          </button>
        </div>
      </WorkflowStep>
    )
  }

  const icon = payment.status === 'PENDING'
    ? <CircleEllipsis size={20} />
    : payment.status === 'SUCCEEDED'
      ? <Check size={20} strokeWidth={2.5} />
      : <CircleX size={20} />

  return (
    <WorkflowStep state="active" icon={icon}>
      <div className="resource-card payment-resource">
        <div className="resource-heading">
          <h2>Payment #{payment.id}</h2>
          <StatusBadge status={payment.status} />
        </div>
        <p>Amount: <strong>{formatMoney(payment.amount)}</strong> {payment.currency}</p>
        <div className="payment-metadata">
          <span>Idempotency Key:</span>
          <code>{idempotencyKey}</code>
          {replayed && <span className="replay-note">Same payment returned on retry</span>}
        </div>
        {payment.status === 'FAILED' && (
          <div className="failure-detail" role="status">
            Failure code: <strong>{payment.failureCode ?? 'UNKNOWN'}</strong>
          </div>
        )}
        {payment.status === 'PENDING' && (
          <div className="payment-actions">
            <button
              className="button button--primary"
              type="button"
              onClick={onSimulateSuccess}
              disabled={requestRunning}
            >
              {busy === 'simulate-success' ? 'Simulating…' : 'Simulate Success'}
            </button>
            <button
              className="button button--secondary"
              type="button"
              onClick={onSimulateFailure}
              disabled={requestRunning}
            >
              {busy === 'simulate-failure' ? 'Simulating…' : 'Simulate Failure'}
            </button>
            <button
              className="button button--secondary"
              type="button"
              onClick={onRetry}
              disabled={requestRunning}
            >
              {busy === 'retry-payment' ? 'Retrying…' : 'Retry Same Request'}
            </button>
          </div>
        )}
      </div>
    </WorkflowStep>
  )
}

function RefundSummary({ payment }: { payment: Payment }) {
  return (
    <dl className="refund-summary">
      <div><dt>Payment Amount</dt><dd>{formatMoney(payment.amount)}</dd></div>
      <div><dt>Refunded Amount</dt><dd>{formatMoney(payment.refundedAmount)}</dd></div>
      <div className="refund-summary__pending">
        <dt>Pending Refund Amount</dt>
        <dd>{formatMoney(payment.pendingRefundAmount)}</dd>
      </div>
      <div><dt>Available to Refund</dt><dd>{formatMoney(payment.availableRefundAmount)}</dd></div>
    </dl>
  )
}

function RefundHistory({ refunds, currentId }: { refunds: Refund[]; currentId?: string }) {
  const visibleRefunds = refunds.filter((refund) => refund.id !== currentId)
  if (visibleRefunds.length === 0) return null

  return (
    <div className="refund-history">
      <div className="refund-history__title">Refund history</div>
      {visibleRefunds.map((refund) => (
        <div className="refund-history__item" key={refund.id}>
          <code>{refund.id}</code>
          <span>{formatMoney(refund.amount)}</span>
          <StatusBadge status={refund.status} />
        </div>
      ))}
    </div>
  )
}

type RefundSectionProps = {
  payment: Payment | null
  refund: Refund | null
  refunds: Refund[]
  amountInput: string
  reasonCode: RefundReasonCode
  idempotencyKey: string
  replayed: boolean
  busy: BusyAction | null
  merchantReady: boolean
  onAmountChange: (value: string) => void
  onReasonChange: (value: RefundReasonCode) => void
  onCreate: () => void
  onRetry: () => void
  onSimulateSuccess: () => void
  onSimulateFailure: () => void
  onCreateAnother: () => void
}

function RefundSection({
  payment,
  refund,
  refunds,
  amountInput,
  reasonCode,
  idempotencyKey,
  replayed,
  busy,
  merchantReady,
  onAmountChange,
  onReasonChange,
  onCreate,
  onRetry,
  onSimulateSuccess,
  onSimulateFailure,
  onCreateAnother,
}: RefundSectionProps) {
  const requestRunning = busy !== null || !merchantReady

  if (payment?.status !== 'SUCCEEDED') {
    const helper = payment?.status === 'PENDING'
      ? 'Payment must succeed before refund'
      : payment?.status === 'FAILED'
        ? 'The failed payment is not refundable'
        : 'Complete a payment first'

    return (
      <WorkflowStep state="inactive" icon={<RefreshCw size={19} />}>
        <h2>Refund</h2>
        <p>{helper}</p>
      </WorkflowStep>
    )
  }

  if (!refund) {
    return (
      <WorkflowStep state="active" icon={<RefreshCw size={19} />}>
        <div className="refund-ready">
          <div className="resource-heading">
            <h2>Refund</h2>
            <span className="resource-status resource-status--success">READY</span>
          </div>
          <div className="refund-payment-id">
            Payment ID: <code>{payment.id}</code>
          </div>
          <div className="refund-form-row">
            <label>
              <span>Refund Amount</span>
              <div className="amount-input">
                <span aria-hidden="true">€</span>
                <input
                  type="text"
                  inputMode="decimal"
                  aria-label="Refund amount"
                  value={amountInput}
                  placeholder="Please enter refund amount"
                  onChange={(event) => onAmountChange(event.target.value)}
                  disabled={requestRunning}
                />
              </div>
            </label>
            <label>
              <span>Reason Code</span>
              <select
                aria-label="Refund reason code"
                value={reasonCode}
                onChange={(event) => onReasonChange(event.target.value as RefundReasonCode)}
                disabled={requestRunning}
              >
                {REFUND_REASONS.map((reason) => (
                  <option key={reason.value} value={reason.value}>{reason.label}</option>
                ))}
              </select>
            </label>
          </div>
          <RefundSummary payment={payment} />
          <div className="developer-metadata refund-key">
            <span>Idempotency Key</span>
            <code>{idempotencyKey}</code>
          </div>
          <button
            className="button button--primary refund-create"
            type="button"
            onClick={onCreate}
            disabled={requestRunning || payment.availableRefundAmount <= 0}
          >
            {busy === 'create-refund' ? 'Creating…' : 'Create Refund'}
          </button>
          <RefundHistory refunds={refunds} />
        </div>
      </WorkflowStep>
    )
  }

  const icon = refund.status === 'PENDING'
    ? <CircleEllipsis size={20} />
    : refund.status === 'SUCCEEDED'
      ? <Check size={20} strokeWidth={2.5} />
      : <CircleX size={20} />

  return (
    <WorkflowStep state="active" icon={icon}>
      <div className="resource-card refund-resource">
        <div className="resource-heading">
          <h2>Refund #{refund.id}</h2>
          <StatusBadge status={refund.status} />
        </div>
        <div className="refund-resource__details">
          <span>Payment ID: <code>{refund.paymentId}</code></span>
          <span>Amount: <strong>{formatMoney(refund.amount)}</strong> {refund.currency}</span>
          <span>Reason: <strong>{refund.reasonCode}</strong></span>
        </div>
        <div className="payment-metadata">
          <span>Idempotency Key:</span>
          <code>{idempotencyKey}</code>
          {replayed && <span className="replay-note">Same refund returned on retry</span>}
        </div>
        {refund.status === 'FAILED' && (
          <div className="failure-detail" role="status">
            Failure code: <strong>{refund.failureCode ?? 'UNKNOWN'}</strong>
          </div>
        )}
        <RefundSummary payment={payment} />
        {refund.status === 'PENDING' && (
          <div className="refund-actions">
            <button
              className="button button--primary"
              type="button"
              onClick={onSimulateSuccess}
              disabled={requestRunning}
            >
              {busy === 'simulate-refund-success' ? 'Simulating…' : 'Simulate Success'}
            </button>
            <button
              className="button button--danger"
              type="button"
              onClick={onSimulateFailure}
              disabled={requestRunning}
            >
              {busy === 'simulate-refund-failure' ? 'Simulating…' : 'Simulate Failure'}
            </button>
            <button
              className="button button--secondary"
              type="button"
              onClick={onRetry}
              disabled={requestRunning}
            >
              {busy === 'retry-refund' ? 'Retrying…' : 'Retry Same Request'}
            </button>
          </div>
        )}
        {refund.status !== 'PENDING' && payment.availableRefundAmount > 0 && (
          <button
            className="button button--secondary refund-another"
            type="button"
            onClick={onCreateAnother}
            disabled={requestRunning}
          >
            Create Another Refund
          </button>
        )}
        <RefundHistory refunds={refunds} currentId={refund.id} />
      </div>
    </WorkflowStep>
  )
}

function SectionTitle({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <h2 className="events-panel__title">
      {icon}
      <span>{children}</span>
    </h2>
  )
}

type EventsPanelProps = {
  events: WebhookEvent[]
  selectedEvent: WebhookEvent | null
  busy: BusyAction | null
  hasPayment: boolean
  merchantReady: boolean
  onRefresh: () => void
  onSelect: (eventId: string) => void
  onRetry: () => void
}

function EventsPanel({
  events,
  selectedEvent,
  busy,
  hasPayment,
  merchantReady,
  onRefresh,
  onSelect,
  onRetry,
}: EventsPanelProps) {
  const requestRunning = busy !== null

  return (
    <aside className="events-panel" aria-label="Webhook event information">
      <section className="events-panel__events">
        <div className="events-panel__heading-row">
          <SectionTitle icon={<Webhook size={19} />}>Webhook Events</SectionTitle>
          <button
            className="events-refresh"
            type="button"
            onClick={onRefresh}
            disabled={!merchantReady || !hasPayment || requestRunning}
          >
            <RefreshCw size={14} aria-hidden="true" />
            {busy === 'refresh-webhooks' ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
        {events.length === 0 ? (
          <div className="empty-events">
            <BellOff size={42} strokeWidth={1.8} aria-hidden="true" />
            <p>{hasPayment ? 'No webhook events yet' : 'Complete a Payment first'}</p>
          </div>
        ) : (
          <div className="webhook-list" aria-label="Webhook events">
            {events.map((event) => (
              <button
                className={`webhook-list__item${selectedEvent?.id === event.id ? ' webhook-list__item--selected' : ''}`}
                type="button"
                key={event.id}
                onClick={() => onSelect(event.id)}
                disabled={requestRunning}
              >
                <span className={`webhook-list__dot webhook-list__dot--${event.status.toLowerCase()}`} />
                <span className="webhook-list__content">
                  <span className="webhook-list__type">{event.type}</span>
                  <span className="webhook-list__metadata">
                    <strong>{event.status}</strong>
                    <span>Attempt: {event.attemptCount}</span>
                  </span>
                  <time dateTime={event.createdAt}>{formatTimestamp(event.createdAt)}</time>
                </span>
              </button>
            ))}
          </div>
        )}
      </section>
      <section className="events-panel__details">
        <SectionTitle icon={<Info size={19} />}>Event Details</SectionTitle>
        {selectedEvent ? (
          <div className="event-details">
            <dl>
              <div><dt>Event ID</dt><dd><code>{selectedEvent.id}</code></dd></div>
              <div><dt>Event Type</dt><dd><code>{selectedEvent.type}</code></dd></div>
              <div><dt>Status</dt><dd><StatusBadge status={selectedEvent.status} /></dd></div>
              <div><dt>Attempt Count</dt><dd>{selectedEvent.attemptCount}</dd></div>
              <div><dt>Created At</dt><dd>{formatTimestamp(selectedEvent.createdAt)}</dd></div>
              <div><dt>Last Attempt At</dt><dd>{formatTimestamp(selectedEvent.lastAttemptAt)}</dd></div>
              <div><dt>Delivered At</dt><dd>{formatTimestamp(selectedEvent.deliveredAt)}</dd></div>
              <div>
                <dt>Last Failure Code</dt>
                <dd className={selectedEvent.lastFailureCode ? 'event-details__failure' : ''}>
                  {selectedEvent.lastFailureCode ?? '—'}
                </dd>
              </div>
              <div><dt>Resource ID</dt><dd><code>{webhookResourceId(selectedEvent)}</code></dd></div>
            </dl>
            {selectedEvent.status === 'FAILED' && (
              <button
                className="button button--primary event-details__action"
                type="button"
                onClick={onRetry}
                disabled={!merchantReady || requestRunning}
              >
                {busy === 'retry-webhook' ? 'Retrying…' : 'Retry Delivery'}
              </button>
            )}
            {selectedEvent.status === 'PENDING' && (
              <p className="event-details__note">Automatic delivery pending</p>
            )}
            {selectedEvent.status === 'DELIVERED' && (
              <p className="event-details__note event-details__note--success">Delivery complete</p>
            )}
          </div>
        ) : (
          <div className="event-placeholder">
            <p>Select an event to view details</p>
          </div>
        )}
      </section>
    </aside>
  )
}

function ApiConsole({ entry }: { entry: ApiConsoleEntry }) {
  const [copiedOutput, setCopiedOutput] = useState<string | null>(null)
  const copyTimer = useRef<number | null>(null)
  const output = entry.state === 'ready'
    ? 'READY: Waiting for request...'
    : JSON.stringify({
        request: entry.request ?? { body: null },
        response: entry.response ?? null,
      }, null, 2)

  useEffect(() => () => {
    if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
  }, [])

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(output)
      setCopiedOutput(output)
      if (copyTimer.current !== null) window.clearTimeout(copyTimer.current)
      copyTimer.current = window.setTimeout(() => {
        setCopiedOutput(null)
        copyTimer.current = null
      }, 1400)
    } catch {
      setCopiedOutput(null)
    }
  }

  return (
    <section className="api-console" aria-labelledby="api-console-heading">
      <div className="api-console__header">
        <div className="api-console__request" id="api-console-heading">
          <span className="api-console__method">{entry.method}</span>
          <span className="api-console__endpoint">{entry.endpoint}</span>
          <span className={`api-console__status api-console__status--${entry.state}`}>
            {entry.status === null ? entry.statusText : `${entry.status} ${entry.statusText.toUpperCase()}`}
          </span>
        </div>
        <button className="api-console__copy" type="button" onClick={() => { void handleCopy() }}>
          <Copy size={15} />
          <span aria-live="polite">{copiedOutput === output ? 'Copied' : 'Copy'}</span>
        </button>
      </div>
      <pre className="api-console__body"><code>{output}</code></pre>
    </section>
  )
}

type RunRequestOptions<T> = {
  headers?: Record<string, string>
  authenticated?: boolean
  consoleResponse?: (data: T) => unknown
}

function App() {
  const [merchant, setMerchant] = useState<Merchant | null>(null)
  const [apiKey, setApiKey] = useState<string | null>(null)
  const [demoWebhookUrl, setDemoWebhookUrl] = useState<string | null>(null)
  const [order, setOrder] = useState<Order | null>(null)
  const [payment, setPayment] = useState<Payment | null>(null)
  const [paymentKey, setPaymentKey] = useState('')
  const [paymentReplayed, setPaymentReplayed] = useState(false)
  const [refund, setRefund] = useState<Refund | null>(null)
  const [refunds, setRefunds] = useState<Refund[]>([])
  const [refundKey, setRefundKey] = useState('')
  const [refundReplayed, setRefundReplayed] = useState(false)
  const [refundAmountInput, setRefundAmountInput] = useState('')
  const [refundReasonCode, setRefundReasonCode] =
    useState<RefundReasonCode>('CUSTOMER_REQUEST')
  const [refundReplayRequest, setRefundReplayRequest] =
    useState<RefundReplayRequest | null>(null)
  const [webhookEvents, setWebhookEvents] = useState<WebhookEvent[]>([])
  const [selectedWebhookEvent, setSelectedWebhookEvent] =
    useState<WebhookEvent | null>(null)
  const [amountInput, setAmountInput] = useState('')
  const [editingOrder, setEditingOrder] = useState(false)
  const [busy, setBusy] = useState<BusyAction | null>(null)
  const requestLock = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [consoleEntry, setConsoleEntry] = useState<ApiConsoleEntry>(READY_CONSOLE)

  function activateDemoApiKey(newApiKey: string) {
    window.sessionStorage.setItem(DEMO_API_KEY_STORAGE_KEY, newApiKey)
    setActiveApiKey(newApiKey)
    setApiKey(newApiKey)
  }

  useEffect(() => {
    let active = true

    async function loadDemoSession() {
      let sessionApiKey = window.sessionStorage.getItem(DEMO_API_KEY_STORAGE_KEY)
      let webhookUrl = PRODUCTION_WEBHOOK_URL

      if (import.meta.env.DEV) {
        try {
          const configResponse = await fetch('/__ledgerpay-demo-config', {
            cache: 'no-store',
          })
          if (configResponse.ok) {
            const config = (await configResponse.json()) as DemoConfig
            if (!sessionApiKey && typeof config.apiKey === 'string' && config.apiKey) {
              sessionApiKey = config.apiKey
            }
            if (typeof config.webhookUrl === 'string') {
              webhookUrl = config.webhookUrl
            }
          }
        } catch {
          // Local development can fall back to creating a new Demo Merchant.
        }
      }

      try {
        if (!sessionApiKey) {
          const result = await createInitialDemoMerchant(webhookUrl)
          if (!active) return
          activateDemoApiKey(result.data.apiKey)
          setMerchant(merchantFromCreation(result.data))
          setDemoWebhookUrl(result.data.webhookUrl ?? webhookUrl)
          return
        }

        setActiveApiKey(sessionApiKey)
        if (!active) return
        window.sessionStorage.setItem(DEMO_API_KEY_STORAGE_KEY, sessionApiKey)
        setApiKey(sessionApiKey)
        setDemoWebhookUrl(webhookUrl)

        const result = await apiRequest<Merchant>('/api/v1/merchant')
        if (active) setMerchant(result.data)
      } catch (loadError) {
        if (!active) return
        setError(`${errorMessage(loadError)} Reset Demo to start a new session.`)
      }
    }

    void loadDemoSession()
    return () => { active = false }
  }, [])

  const requestRunning = busy !== null

  async function runRequest<T>(
    action: BusyAction,
    method: string,
    endpoint: string,
    body?: unknown,
    options: RunRequestOptions<T> = {},
  ): Promise<ApiResult<T> | null> {
    if (requestLock.current) return null
    requestLock.current = true

    const request = {
      ...(options.headers ? { headers: options.headers } : {}),
      body: body ?? null,
    }

    setBusy(action)
    setError(null)
    setConsoleEntry({
      method,
      endpoint,
      status: null,
      statusText: 'REQUESTING',
      request,
      state: 'loading',
    })

    try {
      const result = await apiRequest<T>(endpoint, {
        method,
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        ...(options.headers ? { headers: options.headers } : {}),
      }, {
        authenticated: options.authenticated,
      })
      setConsoleEntry({
        method,
        endpoint,
        status: result.status,
        statusText: result.statusText,
        request,
        response: options.consoleResponse
          ? options.consoleResponse(result.data)
          : result.data,
        state: 'success',
      })
      return result
    } catch (requestError) {
      const apiError = requestError instanceof ApiRequestError
        ? requestError
        : new ApiRequestError(0, 'FRONTEND ERROR', {
            message: errorMessage(requestError),
          })
      setError(apiError.message)
      setConsoleEntry({
        method,
        endpoint,
        status: apiError.status || null,
        statusText: apiError.statusText,
        request,
        response: apiError.data,
        state: 'error',
      })
      return null
    } finally {
      requestLock.current = false
      setBusy(null)
    }
  }

  function showAmountError(method: 'POST' | 'PATCH', endpoint: string) {
    const body = {
      message: 'Enter a monetary amount with no more than two decimal places.',
    }
    setError(body.message)
    setConsoleEntry({
      method,
      endpoint,
      status: null,
      statusText: 'NOT SENT',
      request: { body: { amount: amountInput } },
      response: body,
      state: 'error',
    })
  }

  async function refreshOrder(orderId: string) {
    try {
      const refreshed = await apiRequest<Order>(`/api/v1/orders/${orderId}`)
      setOrder(refreshed.data)
    } catch (refreshError) {
      setError(`The action succeeded, but the Order refresh failed: ${errorMessage(refreshError)}`)
    }
  }

  async function refreshPayment(paymentId: string) {
    try {
      const refreshed = await apiRequest<Payment>(`/api/v1/payments/${paymentId}`)
      setPayment(refreshed.data)
    } catch (refreshError) {
      setError(`The action succeeded, but the Payment refresh failed: ${errorMessage(refreshError)}`)
    }
  }

  async function refreshRefunds(paymentId: string) {
    try {
      const refreshed = await apiRequest<Refund[]>(`/api/v1/payments/${paymentId}/refunds`)
      setRefunds(refreshed.data)
    } catch (refreshError) {
      setError(`The action succeeded, but the Refund history refresh failed: ${errorMessage(refreshError)}`)
    }
  }

  function updateWebhookEvent(event: WebhookEvent) {
    setWebhookEvents((current) => {
      const existing = current.some((item) => item.id === event.id)
      return existing
        ? current.map((item) => item.id === event.id ? event : item)
        : [event, ...current]
    })
    setSelectedWebhookEvent((current) => current?.id === event.id ? event : current)
  }

  async function refreshWebhookEvents(paymentId: string) {
    try {
      const refreshed = await apiRequest<WebhookEvent[]>(
        `/api/v1/payments/${paymentId}/webhook-events`,
      )
      setWebhookEvents(refreshed.data)
      setSelectedWebhookEvent((current) => current
        ? refreshed.data.find((event) => event.id === current.id) ?? null
        : null)
    } catch (refreshError) {
      setError(`The action succeeded, but the WebhookEvent refresh failed: ${errorMessage(refreshError)}`)
    }
  }

  function clearWorkflowState() {
    setOrder(null)
    setPayment(null)
    setPaymentKey('')
    setPaymentReplayed(false)
    setRefund(null)
    setRefunds([])
    setRefundKey('')
    setRefundReplayed(false)
    setRefundAmountInput('')
    setRefundReasonCode('CUSTOMER_REQUEST')
    setRefundReplayRequest(null)
    setWebhookEvents([])
    setSelectedWebhookEvent(null)
    setAmountInput('')
    setEditingOrder(false)
  }

  async function handleRotateApiKey() {
    const result = await runRequest<RotateApiKeyResponse>(
      'rotate-api-key',
      'POST',
      '/api/v1/merchant/api-key/rotate',
      undefined,
      { consoleResponse: redactedKeyResponse },
    )
    if (!result) return

    activateDemoApiKey(result.data.apiKey)
  }

  async function handleDeactivateMerchant() {
    const confirmed = window.confirm(
      'Deactivate this Merchant? Its API key will stop working immediately.',
    )
    if (!confirmed) return

    const result = await runRequest<Merchant>(
      'deactivate-merchant',
      'POST',
      '/api/v1/merchant/deactivate',
    )
    if (!result) return

    setMerchant(result.data)
    setEditingOrder(false)
    if (result.data.status === 'INACTIVE') setActiveApiKey(null)
  }

  async function handleResetDemo() {
    const body = createDemoMerchantBody(
      demoWebhookUrl ?? merchant?.webhookUrl ?? PRODUCTION_WEBHOOK_URL,
    )
    const result = await runRequest<CreateMerchantResponse>(
      'reset-demo',
      'POST',
      '/api/v1/merchants',
      body,
      {
        authenticated: false,
        consoleResponse: redactedKeyResponse,
      },
    )
    if (!result) return

    activateDemoApiKey(result.data.apiKey)
    setMerchant(merchantFromCreation(result.data))
    if (result.data.webhookUrl) setDemoWebhookUrl(result.data.webhookUrl)
    clearWorkflowState()
  }

  async function handleCreateOrder() {
    const amount = parseMinorUnits(amountInput)
    if (amount === null) {
      showAmountError('POST', '/api/v1/orders')
      return
    }

    const result = await runRequest<Order>(
      'create-order',
      'POST',
      '/api/v1/orders',
      { amount },
    )
    if (!result) return

    setOrder(result.data)
    setPayment(null)
    setPaymentKey(createPaymentIdempotencyKey())
    setPaymentReplayed(false)
    setRefund(null)
    setRefunds([])
    setRefundKey('')
    setRefundReplayed(false)
    setRefundAmountInput('')
    setRefundReplayRequest(null)
    setWebhookEvents([])
    setSelectedWebhookEvent(null)
    setAmountInput(amountToInput(result.data.amount))
  }

  async function handleUpdateOrder() {
    if (!order) return
    const amount = parseMinorUnits(amountInput)
    if (amount === null) {
      showAmountError('PATCH', `/api/v1/orders/${order.id}`)
      return
    }

    const endpoint = `/api/v1/orders/${order.id}`
    const result = await runRequest<Order>(
      'update-order',
      'PATCH',
      endpoint,
      { amount },
    )
    if (!result) return

    setOrder(result.data)
    setAmountInput(amountToInput(result.data.amount))
    setEditingOrder(false)
  }

  async function handleCancelOrder() {
    if (!order) return
    const endpoint = `/api/v1/orders/${order.id}/cancel`
    const result = await runRequest<Order>('cancel-order', 'POST', endpoint)
    if (!result) return
    setOrder(result.data)
  }

  async function submitPayment(action: 'create-payment' | 'retry-payment') {
    if (!order || !paymentKey) return
    const previousPaymentId = payment?.id
    const body = { orderId: order.id }
    const result = await runRequest<Payment>(
      action,
      'POST',
      '/api/v1/payments',
      body,
      { headers: { 'Idempotency-Key': paymentKey } },
    )
    if (!result) return

    setPayment(result.data)
    setEditingOrder(false)
    if (action === 'retry-payment') {
      setPaymentReplayed(
        result.status === 200 &&
        (previousPaymentId === undefined || previousPaymentId === result.data.id),
      )
    }
    await refreshOrder(order.id)
  }

  async function simulatePayment(
    action: 'simulate-success' | 'simulate-failure',
    outcome: 'SUCCEEDED' | 'FAILED',
    failureCode?: PaymentFailureCode,
  ) {
    if (!order || !payment) return
    const endpoint = `/api/v1/payments/${payment.id}/simulate`
    const body = {
      outcome,
      ...(failureCode ? { failureCode } : {}),
    }
    const result = await runRequest<Payment>(action, 'POST', endpoint, body)
    if (!result) return

    setPayment(result.data)
    if (outcome === 'SUCCEEDED') {
      setRefund(null)
      setRefunds([])
      setRefundKey(createRefundIdempotencyKey())
      setRefundReplayed(false)
      setRefundAmountInput('')
      setRefundReasonCode('CUSTOMER_REQUEST')
      setRefundReplayRequest(null)
    }
    await Promise.all([
      refreshOrder(order.id),
      refreshWebhookEvents(payment.id),
    ])
  }

  function showRefundAmountError(endpoint: string) {
    const response = {
      message: 'Enter a monetary amount with no more than two decimal places.',
    }
    const request = {
      headers: { 'Idempotency-Key': refundKey },
      body: { amount: refundAmountInput, reasonCode: refundReasonCode },
    }
    setError(response.message)
    setConsoleEntry({
      method: 'POST',
      endpoint,
      status: null,
      statusText: 'NOT SENT',
      request,
      response,
      state: 'error',
    })
  }

  async function submitRefund(action: 'create-refund' | 'retry-refund') {
    if (!order || !payment) return

    let replayRequest: RefundReplayRequest
    if (action === 'retry-refund') {
      if (!refundReplayRequest) return
      replayRequest = refundReplayRequest
    } else {
      const amount = parseMinorUnits(refundAmountInput)
      const endpoint = `/api/v1/payments/${payment.id}/refunds`
      if (amount === null) {
        showRefundAmountError(endpoint)
        return
      }
      replayRequest = {
        paymentId: payment.id,
        amount,
        reasonCode: refundReasonCode,
        idempotencyKey: refundKey,
      }
    }

    const endpoint = `/api/v1/payments/${replayRequest.paymentId}/refunds`
    const body = {
      amount: replayRequest.amount,
      reasonCode: replayRequest.reasonCode,
    }
    const result = await runRequest<Refund>(
      action,
      'POST',
      endpoint,
      body,
      { headers: { 'Idempotency-Key': replayRequest.idempotencyKey } },
    )
    if (!result) return

    const previousRefundId = refund?.id
    setRefund(result.data)
    setRefundReplayRequest(replayRequest)
    if (action === 'retry-refund') {
      setRefundReplayed(
        result.status === 200 &&
        (previousRefundId === undefined || previousRefundId === result.data.id),
      )
    }
    await Promise.all([
      refreshPayment(payment.id),
      refreshOrder(order.id),
      refreshRefunds(payment.id),
    ])
  }

  async function simulateRefund(
    action: 'simulate-refund-success' | 'simulate-refund-failure',
    outcome: 'SUCCEEDED' | 'FAILED',
    failureCode?: RefundFailureCode,
  ) {
    if (!order || !payment || !refund) return

    const endpoint = `/api/v1/refunds/${refund.id}/simulate`
    const body = {
      outcome,
      ...(failureCode ? { failureCode } : {}),
    }
    const result = await runRequest<Refund>(action, 'POST', endpoint, body)
    if (!result) return

    setRefund(result.data)
    await Promise.all([
      refreshPayment(payment.id),
      refreshOrder(order.id),
      refreshRefunds(payment.id),
      refreshWebhookEvents(payment.id),
    ])
  }

  function startAnotherRefund() {
    setRefund(null)
    setRefundKey(createRefundIdempotencyKey())
    setRefundReplayed(false)
    setRefundAmountInput('')
    setRefundReasonCode('CUSTOMER_REQUEST')
    setRefundReplayRequest(null)
    setError(null)
  }

  async function handleRefreshWebhooks() {
    if (!payment) return
    const endpoint = `/api/v1/payments/${payment.id}/webhook-events`
    const result = await runRequest<WebhookEvent[]>(
      'refresh-webhooks',
      'GET',
      endpoint,
    )
    if (!result) return

    setWebhookEvents(result.data)
    setSelectedWebhookEvent((current) => current
      ? result.data.find((event) => event.id === current.id) ?? null
      : null)
  }

  async function handleSelectWebhook(eventId: string) {
    if (merchant?.status === 'INACTIVE') {
      setSelectedWebhookEvent(
        webhookEvents.find((event) => event.id === eventId) ?? null,
      )
      return
    }

    const endpoint = `/api/v1/webhook-events/${eventId}`
    const result = await runRequest<WebhookEvent>(
      'select-webhook',
      'GET',
      endpoint,
    )
    if (!result) return

    setSelectedWebhookEvent(result.data)
    updateWebhookEvent(result.data)
  }

  async function handleRetryWebhook() {
    if (!selectedWebhookEvent) return
    const endpoint = `/api/v1/webhook-events/${selectedWebhookEvent.id}/retry`
    const result = await runRequest<WebhookEvent>(
      'retry-webhook',
      'POST',
      endpoint,
    )
    if (!result) return

    setSelectedWebhookEvent(result.data)
    updateWebhookEvent(result.data)
  }

  return (
    <div className="app-shell">
      <Header busy={busy} onReset={() => { void handleResetDemo() }} />
      <main className="page-content" aria-busy={requestRunning}>
        <MerchantCard
          merchant={merchant}
          apiKey={apiKey}
          busy={busy}
          onRotate={() => { void handleRotateApiKey() }}
          onDeactivate={() => { void handleDeactivateMerchant() }}
        />
        {error && <div className="error-banner" role="alert">{error}</div>}
        <div className={`workspace${merchant?.status === 'INACTIVE' ? ' workspace--merchant-inactive' : ''}`}>
          <div className="workflow" aria-label="Payment workflow">
            <div className="workflow__line" aria-hidden="true" />
            <OrderSection
              order={order}
              payment={payment}
              amountInput={amountInput}
              editing={editingOrder}
              busy={busy}
              merchantReady={merchant?.status === 'ACTIVE'}
              onAmountChange={setAmountInput}
              onCreate={() => { void handleCreateOrder() }}
              onStartEditing={() => {
                if (order) setAmountInput(amountToInput(order.amount))
                setEditingOrder(true)
              }}
              onStopEditing={() => {
                if (order) setAmountInput(amountToInput(order.amount))
                setEditingOrder(false)
              }}
              onUpdate={() => { void handleUpdateOrder() }}
              onCancel={() => { void handleCancelOrder() }}
            />
            <PaymentSection
              order={order}
              payment={payment}
              idempotencyKey={paymentKey}
              replayed={paymentReplayed}
              busy={busy}
              merchantReady={merchant?.status === 'ACTIVE'}
              onCreate={() => { void submitPayment('create-payment') }}
              onRetry={() => { void submitPayment('retry-payment') }}
              onSimulateSuccess={() => {
                void simulatePayment('simulate-success', 'SUCCEEDED')
              }}
              onSimulateFailure={() => {
                void simulatePayment('simulate-failure', 'FAILED', 'PAYMENT_DECLINED')
              }}
            />
            <RefundSection
              payment={payment}
              refund={refund}
              refunds={refunds}
              amountInput={refundAmountInput}
              reasonCode={refundReasonCode}
              idempotencyKey={refundKey}
              replayed={refundReplayed}
              busy={busy}
              merchantReady={merchant?.status === 'ACTIVE'}
              onAmountChange={setRefundAmountInput}
              onReasonChange={setRefundReasonCode}
              onCreate={() => { void submitRefund('create-refund') }}
              onRetry={() => { void submitRefund('retry-refund') }}
              onSimulateSuccess={() => {
                void simulateRefund('simulate-refund-success', 'SUCCEEDED')
              }}
              onSimulateFailure={() => {
                void simulateRefund(
                  'simulate-refund-failure',
                  'FAILED',
                  'REFUND_PROCESSING_ERROR',
                )
              }}
              onCreateAnother={startAnotherRefund}
            />
          </div>
          <EventsPanel
            events={webhookEvents}
            selectedEvent={selectedWebhookEvent}
            busy={busy}
            hasPayment={payment !== null}
            merchantReady={merchant?.status === 'ACTIVE'}
            onRefresh={() => { void handleRefreshWebhooks() }}
            onSelect={(eventId) => { void handleSelectWebhook(eventId) }}
            onRetry={() => { void handleRetryWebhook() }}
          />
        </div>
        <ApiConsole entry={consoleEntry} />
      </main>
    </div>
  )
}

export default App
