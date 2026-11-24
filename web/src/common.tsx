import {
  useCallback,
  useEffect,
  useState,
  type AnchorHTMLAttributes,
  type ReactNode,
} from 'react'
import { AlertCircle, Coffee, LoaderCircle, Sprout } from 'lucide-react'
import {
  ApiError,
  errorMessage,
  statusNames,
  type Product,
  type Status,
} from './api'

export const paths = {
  shop: '/',
  cart: '/?area=carrinho',
  orders: '/?area=pedidos',
  ops: '/?area=operacoes',
  order: (id: string) => '/?pedido=' + encodeURIComponent(id),
}
export function navigate(href: string) {
  history.pushState({}, '', href)
  window.dispatchEvent(new PopStateEvent('popstate'))
  window.scrollTo({ top: 0, behavior: 'instant' })
}
export function Link({
  href = '/',
  onClick,
  ...props
}: AnchorHTMLAttributes<HTMLAnchorElement>) {
  return (
    <a
      href={href}
      {...props}
      onClick={(event) => {
        onClick?.(event)
        if (
          event.defaultPrevented ||
          event.ctrlKey ||
          event.metaKey ||
          event.altKey ||
          event.shiftKey ||
          event.button !== 0 ||
          props.target
        )
          return
        event.preventDefault()
        navigate(href)
      }}
    />
  )
}
export function readStored<T>(key: string, fallback: T): T {
  try {
    return (
      JSON.parse(sessionStorage.getItem('casa-torra:' + key) ?? 'null') ??
      fallback
    )
  } catch {
    return fallback
  }
}
export function saveStored(key: string, value: unknown) {
  try {
    sessionStorage.setItem('casa-torra:' + key, JSON.stringify(value))
  } catch {
    /* Permite navegar com armazenamento bloqueado. */
  }
}

export function useResource<T>(load: () => Promise<T>, pollMs?: number) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const reload = useCallback(() => setRevision((value) => value + 1), [])
  useEffect(() => {
    let active = true
    let timer: number | undefined
    let triedRenewal = false
    async function run() {
      try {
        const next = await load()
        if (active) {
          setData(next)
          setError('')
          triedRenewal = false
        }
      } catch (problem) {
        if (!active) return
        setError(errorMessage(problem))
        if (
          problem instanceof ApiError &&
          (problem.status === 401 || problem.status === 404)
        ) {
          setData(null)
          if (problem.recoverSession && !triedRenewal) {
            triedRenewal = true
            try {
              await problem.recoverSession()
              if (active) void run()
            } catch (renewalError) {
              if (active) setError(errorMessage(renewalError))
            }
          }
          return
        }
      }
      if (active && pollMs) timer = window.setTimeout(run, pollMs)
    }
    void run()
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [load, pollMs, revision])
  return { data, setData, error, reload }
}
export function Brand() {
  return (
    <Link href={paths.shop} aria-label="Casa Torra, início" className="brand">
      <span className="brand-symbol">
        <Sprout size={23} strokeWidth={1.6} />
      </span>
      Casa Torra<span className="brand-dot">.</span>
    </Link>
  )
}
export function Notice({
  children,
  retry,
}: {
  children: ReactNode
  retry?: () => void
}) {
  return (
    <div role="alert" className="notice">
      <AlertCircle size={18} />
      <span>{children}</span>
      {retry && (
        <button className="text-btn" onClick={retry}>
          Tentar novamente
        </button>
      )}
    </div>
  )
}
export function Loading({
  children = 'Carregando…',
}: {
  children?: ReactNode
}) {
  return (
    <p role="status" className="loading">
      <LoaderCircle size={19} />
      {children}
    </p>
  )
}
export function Empty({
  icon,
  title,
  children,
}: {
  icon?: ReactNode
  title: string
  children: ReactNode
}) {
  return (
    <div className="empty">
      <span className="empty-icon">{icon ?? <Coffee />}</span>
      <h2>{title}</h2>
      {children}
    </div>
  )
}
export function StatusBadge({
  status,
  testId,
}: {
  status: Status
  testId?: string
}) {
  return (
    <span
      className={'status status-' + status}
      data-testid={testId}
      data-status={status}
    >
      <span className="status-dot" />
      {statusNames[status]}
    </span>
  )
}
const imageKeys = new Set([
  'cafe',
  'filtro',
  'caneca',
  'moedor',
  'balanca',
  'prensa',
])
export function ProductImage({
  product,
}: {
  product: Pick<Product, 'imageKey'>
}) {
  const [failed, setFailed] = useState(false)
  return (
    <div className="product-image">
      {!failed && imageKeys.has(product.imageKey) ? (
        <img
          alt=""
          loading="lazy"
          src={'/images/' + product.imageKey + '.webp'}
          onError={() => setFailed(true)}
        />
      ) : (
        <Coffee aria-hidden="true" className="image-fallback" />
      )}
    </div>
  )
}
