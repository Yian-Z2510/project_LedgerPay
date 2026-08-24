export type ApiResult<T> = {
  status: number
  statusText: string
  data: T
}

let activeApiKey: string | null = null

export function setActiveApiKey(apiKey: string | null) {
  activeApiKey = apiKey
}

export class ApiRequestError extends Error {
  status: number
  statusText: string
  data: unknown

  constructor(status: number, statusText: string, data: unknown) {
    const message =
      typeof data === 'object' && data !== null && 'message' in data
        ? String(data.message)
        : statusText
    super(message || 'The request failed.')
    this.name = 'ApiRequestError'
    this.status = status
    this.statusText = statusText
    this.data = data
  }
}

export async function apiRequest<T>(
  endpoint: string,
  init: RequestInit = {},
  options: { authenticated?: boolean } = {},
): Promise<ApiResult<T>> {
  let response: Response
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  if (options.authenticated !== false && activeApiKey) {
    headers.set('Authorization', `Bearer ${activeApiKey}`)
  }

  try {
    response = await fetch(endpoint, {
      ...init,
      headers,
    })
  } catch {
    throw new ApiRequestError(0, 'NETWORK ERROR', {
      message: 'Could not reach the LedgerPay API.',
    })
  }

  const contentType = response.headers.get('content-type') ?? ''
  const data: unknown = contentType.includes('application/json')
    ? await response.json()
    : await response.text()

  if (!response.ok) {
    throw new ApiRequestError(response.status, response.statusText, data)
  }

  return {
    status: response.status,
    statusText: response.statusText,
    data: data as T,
  }
}
