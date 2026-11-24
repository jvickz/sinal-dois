import { useState, type FormEvent } from 'react'
import {
  ArrowRight,
  Boxes,
  ClipboardList,
  Coffee,
  LogOut,
  Package,
  Truck,
} from 'lucide-react'
import {
  api,
  ApiError,
  dateTime,
  errorMessage,
  money,
  statusNames,
  type Session,
  type Status,
} from './api'
import { Empty, Loading, Notice, StatusBadge, useResource } from './common'
import { ItemSummary } from './orders'

interface OpsProps {
  session: Session
  update: (session: Session) => void
}
function Login({ session, update }: OpsProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem('')
    try {
      update(await api.login(username, password, session.csrfToken))
    } catch (problem) {
      setProblem(
        problem instanceof ApiError && problem.status === 401
          ? 'Usuário ou senha incorretos.'
          : errorMessage(problem),
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="login-layout">
      <form className="panel login" onSubmit={submit}>
        <h2>Acesso do operador</h2>
        <label htmlFor="username">Usuário</label>
        <input
          id="username"
          name="username"
          autoComplete="username"
          required
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
        <label htmlFor="password">Senha</label>
        <input
          id="password"
          name="password"
          autoComplete="current-password"
          type="password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {problem && <Notice>{problem}</Notice>}
        <button className="button primary full" disabled={busy}>
          {busy ? 'Entrando…' : 'Entrar'}
          <ArrowRight size={16} />
        </button>
      </form>
      <aside className="credentials">
        <Coffee size={24} />
        <h2>Conta de demonstração</h2>
        <p>
          Use estas credenciais para acompanhar o estoque e expedir os pedidos
          da loja.
        </p>
        <dl>
          <dt>Usuário</dt>
          <dd>operador</dd>
          <dt>Senha</dt>
          <dd>demo-operacoes</dd>
        </dl>
      </aside>
    </div>
  )
}
function Dashboard({ session, update }: OpsProps) {
  const orders = useResource(api.operations, 2500)
  const inventory = useResource(api.catalog, 5000)
  const [filter, setFilter] = useState<Status | ''>('')
  const [busy, setBusy] = useState('')
  const [problem, setProblem] = useState('')
  const [notice, setNotice] = useState('')
  async function action(id: string, operation: 'pick' | 'ship') {
    if (busy) return
    setBusy(id)
    setNotice('')
    setProblem('')
    try {
      await api.advance(id, operation, session.csrfToken)
      setNotice(
        operation === 'pick'
          ? 'Pedido encaminhado para separação.'
          : 'Expedição registrada.',
      )
    } catch (problem) {
      setProblem(errorMessage(problem))
    } finally {
      setBusy('')
      orders.reload()
      inventory.reload()
    }
  }
  async function logout() {
    if (busy) return
    setBusy('logout')
    setProblem('')
    try {
      update(await api.logout(session.csrfToken))
    } catch (problem) {
      setProblem(errorMessage(problem))
    } finally {
      setBusy('')
    }
  }
  const selected = orders.data?.filter(
    (order) => !filter || order.status === filter,
  )
  return (
    <>
      <div className="ops-bar">
        <span>
          <span className="status-dot" />
          Operador conectado
        </span>
        <button
          className="text-btn"
          disabled={!!busy}
          onClick={() => void logout()}
        >
          <LogOut size={16} />
          Sair
        </button>
      </div>
      {orders.data && (
        <div className="metrics">
          {[
            {
              name: 'Pedidos recebidos',
              count: orders.data.length,
              icon: <ClipboardList />,
            },
            {
              name: 'A separar',
              count: orders.data.filter((order) => order.status === 'PAID')
                .length,
              icon: <Boxes />,
            },
            {
              name: 'Em separação',
              count: orders.data.filter((order) => order.status === 'PICKING')
                .length,
              icon: <Package />,
            },
            {
              name: 'Expedidos',
              count: orders.data.filter((order) => order.status === 'SHIPPED')
                .length,
              icon: <Truck />,
            },
          ].map((metric) => (
            <div key={metric.name}>
              <span>
                {metric.icon}
                {metric.name}
              </span>
              <strong>{metric.count}</strong>
            </div>
          ))}
        </div>
      )}
      {problem && <Notice>{problem}</Notice>}
      {notice && (
        <p className="confirmation" role="status">
          {notice}
        </p>
      )}
      <section className="panel ops-orders">
        <div className="panel-title">
          <h2>Fila de pedidos</h2>
          <label className="filter-label" htmlFor="status-filter">
            Filtrar por situação
            <select
              id="status-filter"
              value={filter}
              onChange={(event) => setFilter(event.target.value as Status | '')}
            >
              <option value="">Todas as situações</option>
              {Object.entries(statusNames).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {orders.error && <Notice retry={orders.reload}>{orders.error}</Notice>}
        {!orders.error && !selected && <Loading />}
        {selected &&
          (selected.length ? (
            <ul className="ops-list">
              {selected.map((order) => (
                <li key={order.id} data-testid={'order-' + order.id}>
                  <div className="ops-order-head">
                    <span className="order-number">
                      #{order.id.slice(0, 8)}
                      <small>{dateTime(order.createdAt)}</small>
                    </span>
                    <StatusBadge status={order.status} />
                    <strong>{money(order.totalCents)}</strong>
                    {order.status === 'PAID' && (
                      <button
                        className="button secondary"
                        disabled={!!busy}
                        onClick={() => void action(order.id, 'pick')}
                      >
                        Separar pedido
                      </button>
                    )}
                    {order.status === 'PICKING' && (
                      <button
                        className="button secondary"
                        disabled={!!busy}
                        onClick={() => void action(order.id, 'ship')}
                      >
                        Expedir pedido
                      </button>
                    )}
                  </div>
                  <details>
                    <summary>Ver itens do pedido</summary>
                    <p className="order-id">{order.id}</p>
                    <ItemSummary order={order} />
                  </details>
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted">Nenhum pedido nesta situação.</p>
          ))}
      </section>
      <section className="panel">
        <div className="panel-title">
          <h2>Estoque disponível</h2>
          <Boxes size={22} />
        </div>
        {inventory.error && (
          <Notice retry={inventory.reload}>{inventory.error}</Notice>
        )}
        {!inventory.error && !inventory.data && <Loading />}
        {inventory.data &&
          (inventory.data.length ? (
            <div className="inventory-grid">
              {inventory.data.map((product) => (
                <div key={product.sku} data-testid={'inventory-' + product.sku}>
                  <div>
                    <strong>{product.name}</strong>
                    <small>{product.sku}</small>
                  </div>
                  <span
                    data-testid={'stock-' + product.sku}
                    aria-label={product.available + ' disponíveis'}
                  >
                    {product.available}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <Empty title="Estoque vazio">
              <p>Nenhum produto cadastrado.</p>
            </Empty>
          ))}
      </section>
    </>
  )
}
export function Operations(props: OpsProps) {
  return (
    <section className="wrap page">
      <div className="page-heading">
        <span className="eyebrow">
          <ClipboardList size={16} /> CASA TORRA
        </span>
        <h1>
          Operações<span>.</span>
        </h1>
        <p>Gestão de pedidos, separação e expedição.</p>
      </div>
      {props.session.operator ? <Dashboard {...props} /> : <Login {...props} />}
    </section>
  )
}
