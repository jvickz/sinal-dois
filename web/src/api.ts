export type Status =
  | 'AWAITING_PAYMENT'
  | 'PAID'
  | 'PICKING'
  | 'SHIPPED'
  | 'PAYMENT_FAILED'
  | 'CANCELLED'
  | 'EXPIRED'
export type Outcome = 'APPROVED' | 'DECLINED'
export interface Product {
  sku: string
  name: string
  description: string
  category: string
  priceCents: number
  available: number
  imageKey: string
  brand: string
  specs: string[]
}
export interface OrderItem {
  sku: string
  name: string
  quantity: number
  unitPriceCents: number
}
export interface Order {
  id: string
  status: Status
  items: OrderItem[]
  totalCents: number
  createdAt: string
  expiresAt: string
  timeline: { type: string; label: string; at: string }[]
}
export interface Session {
  csrfToken: string
  operator: boolean
}
export interface PaymentEvent {
  eventId: string
  outcome: Outcome
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    detail: string,
    public readonly recoverSession?: () => Promise<void>,
  ) {
    super(detail)
  }
}

let renewal: Promise<void> | undefined
function renewBuyerSession(): Promise<void> {
  if (!renewal) {
    renewal = request<Session>('/session')
      .then((session) => {
        window.dispatchEvent(
          new CustomEvent('sinaldois:session-ready', { detail: session }),
        )
      })
      .finally(() => {
        renewal = undefined
      })
  }
  return renewal
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch('/api/v1' + path, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
    })
  } catch {
    throw new Error(
      'O servidor está fora de alcance. Confira sua conexão e tente novamente.',
    )
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (
      (response.status === 403 && path !== '/session') ||
      (response.status === 401 &&
        path.startsWith('/operator/') &&
        path !== '/operator/session')
    ) {
      window.dispatchEvent(new Event('sinaldois:session'))
    }
    if (response.status === 403) {
      throw new ApiError(
        403,
        problem.code ?? '',
        'Sua sessão foi atualizada. Tente novamente.',
      )
    }
    throw new ApiError(
      response.status,
      problem.code ?? '',
      problem.detail || 'O servidor não conseguiu concluir esta solicitação.',
      response.status === 401 &&
      problem.code === 'SESSION_REQUIRED' &&
      (options.method ?? 'GET') === 'GET' &&
      (path === '/orders' || path.startsWith('/orders/'))
        ? renewBuyerSession
        : undefined,
    )
  }
  const body = await response.text()
  return body ? (JSON.parse(body) as T) : (undefined as T)
}

function mutation<T>(
  path: string,
  token: string,
  body?: unknown,
  extra: Record<string, string> = {},
  method = 'POST',
) {
  return request<T>(path, {
    method,
    headers: {
      'X-CSRF-Token': token,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...extra,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

const orderPath = (id: string) => '/orders/' + encodeURIComponent(id)
export const api = {
  session: () => request<Session>('/session'),
  catalog: () => request<Product[]>('/catalog'),
  orders: () => request<Order[]>('/orders'),
  order: (id: string) => request<Order>(orderPath(id)),
  checkout: (
    items: { sku: string; quantity: number }[],
    key: string,
    token: string,
  ) => mutation<Order>('/orders', token, { items }, { 'Idempotency-Key': key }),
  cancel: (id: string, token: string) =>
    mutation<Order>(orderPath(id) + '/cancel', token),
  payment: (id: string, event: PaymentEvent, token: string) =>
    mutation<unknown>(orderPath(id) + '/payment-events', token, event),
  login: (username: string, password: string, token: string) =>
    mutation<Session>('/operator/session', token, { username, password }),
  logout: (token: string) =>
    mutation<Session>('/operator/session', token, undefined, {}, 'DELETE'),
  operations: () => request<Order[]>('/operator/orders'),
  advance: (id: string, action: 'pick' | 'ship', token: string) =>
    mutation<Order>('/operator' + orderPath(id) + '/' + action, token),
}
export const money = (cents: number) =>
  (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
export const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
export const statusNames: Record<Status, string> = {
  AWAITING_PAYMENT: 'Aguardando pagamento',
  PAID: 'Pago',
  PICKING: 'Em separação',
  SHIPPED: 'Expedido',
  PAYMENT_FAILED: 'Pagamento recusado',
  CANCELLED: 'Cancelado',
  EXPIRED: 'Expirado',
}
export const errorMessage = (error: unknown) =>
  error instanceof Error
    ? error.message
    : 'Algo deu errado ao carregar os dados.'
