import { useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronRight,
  Clock3,
  CreditCard,
  Package,
  RotateCw,
  X,
} from 'lucide-react'
import {
  api,
  dateTime,
  errorMessage,
  money,
  type Order,
  type Outcome,
  type PaymentEvent,
  type Session,
} from './api'
import {
  Empty,
  Link,
  Loading,
  Notice,
  StatusBadge,
  paths,
  readStored,
  saveStored,
  useResource,
} from './common'

export function OrderHistory() {
  const { data, error, reload } = useResource(api.orders, 5000)
  return (
    <section className="wrap page">
      <div className="page-heading">
        <span className="eyebrow">
          <Package size={15} /> ACOMPANHAMENTO
        </span>
        <h1>
          Meus pedidos<span>.</span>
        </h1>
        <p>Consulte os pedidos feitos neste navegador.</p>
      </div>
      {error && <Notice retry={reload}>{error}</Notice>}
      {!error && !data && <Loading>Buscando pedidos…</Loading>}
      {data &&
        (data.length ? (
          <div className="panel list-panel">
            {data.map((order) => (
              <Link
                key={order.id}
                href={paths.order(order.id)}
                className="history-row"
              >
                <span className="order-number">
                  #{order.id.slice(0, 8)}
                  <small>{dateTime(order.createdAt)}</small>
                </span>
                <StatusBadge status={order.status} />
                <strong>{money(order.totalCents)}</strong>
                <ChevronRight size={18} />
              </Link>
            ))}
          </div>
        ) : (
          <Empty icon={<Package />} title="Você ainda não fez um pedido">
            <p>Seus pedidos aparecerão aqui depois do checkout.</p>
            <Link className="button primary" href={paths.shop}>
              Ir para a loja
              <ArrowRight size={16} />
            </Link>
          </Empty>
        ))}
    </section>
  )
}

export function ItemSummary({ order }: { order: Order }) {
  return (
    <>
      <ul className="item-list">
        {order.items.map((item) => (
          <li key={item.sku}>
            <div>
              <span>{item.name}</span>
              <small>
                {item.quantity} × {money(item.unitPriceCents)}
              </small>
            </div>
            <strong>{money(item.quantity * item.unitPriceCents)}</strong>
          </li>
        ))}
      </ul>
      <div className="total">
        <span>Total</span>
        <strong>{money(order.totalCents)}</strong>
      </div>
    </>
  )
}
function Deadline({ iso }: { iso: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.ceil((Date.parse(iso) - now) / 1000))
  return (
    <div className="deadline">
      <Clock3 size={19} />
      <span>
        {seconds > 0 ? 'Reserva válida por ' : 'Prazo da reserva encerrado'}
        {seconds > 0 && (
          <b>
            {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}
          </b>
        )}
        <small>Vencimento em {dateTime(iso)}</small>
      </span>
    </div>
  )
}

export function OrderDetail({ id, session }: { id: string; session: Session }) {
  const load = useCallback(() => api.order(id), [id])
  const resource = useResource(load, 1500)
  const order = resource.data
  const [lastEvent, setLastEvent] = useState<PaymentEvent | null>(() =>
    readStored('event:' + id, null),
  )
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [problem, setProblem] = useState('')
  async function run(action: () => Promise<unknown>, message: string) {
    if (busy) return
    setBusy(true)
    setProblem('')
    setNotice('')
    try {
      await action()
      setNotice(message)
      resource.reload()
    } catch (problem) {
      setProblem(errorMessage(problem))
      resource.reload()
    } finally {
      setBusy(false)
    }
  }
  function simulate(outcome?: Outcome) {
    if (busy) return
    const event = outcome
      ? { eventId: crypto.randomUUID(), outcome }
      : lastEvent
    if (!event) return
    setLastEvent(event)
    saveStored('event:' + id, event)
    void run(
      () => api.payment(id, event, session.csrfToken),
      outcome
        ? 'Evento enviado. Acompanhe o processamento no histórico.'
        : 'O mesmo evento foi reenviado.',
    )
  }
  return (
    <section className="wrap page">
      <Link className="back" href={paths.orders}>
        <ArrowLeft size={16} />
        Meus pedidos
      </Link>
      <div className="page-heading">
        <span className="eyebrow">
          <Package size={15} /> ACOMPANHAMENTO
        </span>
        <h1>
          Seu pedido<span>.</span>
        </h1>
        <p className="order-id">Pedido #{id}</p>
      </div>
      {resource.error && (
        <Notice retry={resource.reload}>{resource.error}</Notice>
      )}
      {!resource.error && !order && <Loading>Consultando o pedido…</Loading>}
      {order && (
        <div className="split">
          <div className="stack">
            <section className="panel">
              <div className="panel-title">
                <h2>Situação do pedido</h2>
                <StatusBadge status={order.status} testId="order-status" />
              </div>
              {order.status === 'AWAITING_PAYMENT' && (
                <Deadline iso={order.expiresAt} />
              )}
              <h3 className="subheading">Histórico</h3>
              <ol className="timeline" data-testid="timeline">
                {order.timeline.map((event, index) => (
                  <li key={event.at + '-' + index}>
                    <span className="timeline-dot" />
                    <strong>{event.label}</strong>
                    <time dateTime={event.at}>{dateTime(event.at)}</time>
                  </li>
                ))}
              </ol>
              {!order.timeline.length && <p>Aguardando o primeiro registro.</p>}
            </section>
            {(order.status === 'AWAITING_PAYMENT' || lastEvent) && (
              <section className="panel payment">
                <div className="panel-title">
                  <h2>Simular pagamento</h2>
                  <CreditCard size={22} />
                </div>
                <p>Escolha o resultado do pagamento de demonstração.</p>
                {order.status === 'AWAITING_PAYMENT' && (
                  <div className="actions">
                    <button
                      className="button primary"
                      disabled={busy}
                      onClick={() => simulate('APPROVED')}
                    >
                      <Check size={16} />
                      Simular aprovação
                    </button>
                    <button
                      className="button secondary"
                      disabled={busy}
                      onClick={() => simulate('DECLINED')}
                    >
                      <X size={16} />
                      Simular recusa
                    </button>
                  </div>
                )}
                {lastEvent && (
                  <div className="replay">
                    <small>
                      Último evento de{' '}
                      {lastEvent.outcome === 'APPROVED'
                        ? 'aprovação'
                        : 'recusa'}
                    </small>
                    <code>{lastEvent.eventId}</code>
                    <button
                      className="text-btn"
                      disabled={busy}
                      onClick={() => simulate()}
                    >
                      <RotateCw size={15} />
                      Reenviar mesmo evento
                    </button>
                  </div>
                )}
              </section>
            )}
            {notice && (
              <p className="confirmation" role="status">
                <Check size={17} />
                {notice}
              </p>
            )}
            {problem && <Notice>{problem}</Notice>}
          </div>
          <aside className="panel summary">
            <h2>Resumo da compra</h2>
            <ItemSummary order={order} />
            {order.status === 'AWAITING_PAYMENT' && (
              <button
                className="text-btn cancel"
                disabled={busy}
                onClick={() =>
                  void run(
                    () => api.cancel(id, session.csrfToken),
                    'Seu pedido foi cancelado.',
                  )
                }
              >
                Cancelar pedido
              </button>
            )}
            <Link className="back" href={paths.shop}>
              <ArrowLeft size={15} />
              Continuar na loja
            </Link>
          </aside>
        </div>
      )}
    </section>
  )
}
