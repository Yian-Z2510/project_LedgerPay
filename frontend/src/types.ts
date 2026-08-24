export type Merchant = {
  id: string
  name: string
  email: string
  status: 'ACTIVE' | 'INACTIVE'
  webhookUrl: string | null
  deactivatedAt: string | null
  createdAt: string
  updatedAt: string
}

export type CreateMerchantResponse = Merchant & {
  apiKey: string
}

export type RotateApiKeyResponse = {
  apiKey: string
}

export type OrderStatus =
  | 'CREATED'
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'CANCELLED'

export type Order = {
  id: string
  amount: number
  currency: 'EUR'
  status: OrderStatus
  cancelledAt: string | null
  createdAt: string
  updatedAt: string
}

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'
export type PaymentFailureCode = 'PAYMENT_DECLINED' | 'PROCESSING_ERROR'

export type Payment = {
  id: string
  orderId: string
  amount: number
  currency: 'EUR'
  status: PaymentStatus
  refundedAmount: number
  pendingRefundAmount: number
  availableRefundAmount: number
  failureCode: PaymentFailureCode | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

export type RefundStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'
export type RefundReasonCode =
  | 'CUSTOMER_REQUEST'
  | 'DUPLICATE_CHARGE'
  | 'PRODUCT_NOT_RECEIVED'
  | 'OTHER'
export type RefundFailureCode = 'REFUND_PROCESSING_ERROR'

export type Refund = {
  id: string
  paymentId: string
  amount: number
  currency: 'EUR'
  reasonCode: RefundReasonCode
  status: RefundStatus
  failureCode: RefundFailureCode | null
  createdAt: string
  updatedAt: string
}

export type WebhookStatus = 'PENDING' | 'DELIVERED' | 'FAILED'
export type WebhookFailureCode =
  | 'WEBHOOK_URL_NOT_CONFIGURED'
  | 'CONNECTION_TIMEOUT'
  | 'HTTP_ERROR'
  | 'PROCESSING_ERROR'
export type WebhookEventType =
  | 'payment.succeeded'
  | 'payment.failed'
  | 'refund.succeeded'
  | 'refund.failed'

export type WebhookEvent = {
  id: string
  type: WebhookEventType
  status: WebhookStatus
  attemptCount: number
  lastAttemptAt: string | null
  deliveredAt: string | null
  lastFailureCode: WebhookFailureCode | null
  createdAt: string
  data: Record<string, unknown>
}

export type ApiErrorBody = {
  code: string
  message: string
}

export type ApiConsoleEntry = {
  method: string
  endpoint: string
  status: number | null
  statusText: string
  request?: {
    headers?: Record<string, string>
    body?: unknown
  }
  response?: unknown
  state: 'ready' | 'loading' | 'success' | 'error'
}
