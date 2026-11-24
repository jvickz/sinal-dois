import { useState } from 'react'
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  Coffee,
  Leaf,
  Minus,
  Plus,
  ShoppingBag,
  Trash2,
} from 'lucide-react'
import {
  api,
  ApiError,
  errorMessage,
  money,
  type Product,
  type Session,
} from './api'
import {
  Empty,
  Link,
  Loading,
  Notice,
  ProductImage,
  navigate,
  paths,
  readStored,
  saveStored,
} from './common'

export type Cart = Record<string, number>
interface StockProps {
  catalog: Product[] | null
  error: string
  reload: () => void
  cart: Cart
  change: (sku: string, quantity: number) => void
}
const stockLabel = (available: number) =>
  available === 0
    ? 'Esgotado'
    : available === 1
      ? 'Última unidade'
      : available + ' disponíveis'
const shelf = ['cafe', 'filtro', 'caneca', 'moedor', 'balanca', 'prensa']

export function Shop({ catalog, error, reload, cart, change }: StockProps) {
  const [category, setCategory] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const categories = Array.from(
    new Set((catalog ?? []).map((product) => product.category)),
  )
  const visible = (catalog ?? [])
    .filter((product) => !category || product.category === category)
    .sort((a, b) => shelf.indexOf(a.imageKey) - shelf.indexOf(b.imageKey))
  return (
    <>
      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <span className="eyebrow">
            <span className="rule" /> CAFÉS & UTENSÍLIOS
          </span>
          <h1 id="hero-title">
            O ritual <br />
            do <em>café.</em>
          </h1>
          <p>
            Do grão à xícara, uma seleção para preparar e saborear seu café em
            casa.
          </p>
          <a className="button primary" href="#catalogo">
            Explorar a seleção <ArrowDown size={16} />
          </a>
        </div>
        <div
          className="hero-photo"
          role="img"
          aria-label="Café passado em coador de cerâmica sobre uma jarra de vidro, à luz da manhã"
        />
      </section>
      <section
        id="catalogo"
        className="catalog-section wrap"
        aria-labelledby="catalog-title"
      >
        <div className="section-title">
          <h2 id="catalog-title">
            Na prateleira<span>.</span>
          </h2>
          {catalog && (
            <span className="result-count">
              {visible.length} {visible.length === 1 ? 'produto' : 'produtos'}
            </span>
          )}
        </div>
        {!!categories.length && (
          <div className="filters" aria-label="Categorias de produtos">
            {[null, ...categories].map((value) => (
              <button
                key={value ?? 'all'}
                aria-pressed={value === category}
                onClick={() => setCategory(value)}
              >
                {value ?? 'Todos'}
              </button>
            ))}
          </div>
        )}
        {error && <Notice retry={reload}>{error}</Notice>}
        {!catalog && !error && <Loading>Carregando a seleção…</Loading>}
        {catalog && !visible.length && (
          <Empty title="Prateleira vazia">
            <p>Nenhum produto disponível nesta seleção.</p>
          </Empty>
        )}
        <div className="product-grid">
          {visible.map((product) => (
            <article
              key={product.sku}
              className="product-card"
              data-testid={'product-' + product.sku}
            >
              <div className="product-visual">
                <ProductImage product={product} />
                <span
                  className={
                    'stock-sticker' + (product.available <= 1 ? ' low' : '')
                  }
                >
                  <i />
                  {stockLabel(product.available)}
                </span>
              </div>
              <div className="product-details">
                <span className="eyebrow small">{product.category}</span>
                <h3>{product.name}</h3>
                <p>{product.description}</p>
                <div className="product-action">
                  <strong>{money(product.priceCents)}</strong>
                  <button
                    className="add"
                    disabled={product.available <= (cart[product.sku] ?? 0)}
                    aria-label={'Adicionar ' + product.name + ' ao carrinho'}
                    data-testid={'add-' + product.sku}
                    onClick={() => {
                      change(product.sku, (cart[product.sku] ?? 0) + 1)
                      setMessage(product.name + ' adicionado ao carrinho.')
                    }}
                  >
                    <Plus size={15} />
                    {product.available === 0
                      ? 'Esgotado'
                      : (cart[product.sku] ?? 0) >= product.available
                        ? 'No carrinho'
                        : 'Adicionar'}
                  </button>
                </div>
              </div>
            </article>
          ))}
        </div>
        <div role="status" className="sr-only">
          {message}
        </div>
      </section>
    </>
  )
}

export function CartPage({
  catalog,
  error,
  reload,
  cart,
  change,
  clear,
  session,
}: StockProps & { clear: () => void; session: Session }) {
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState('')
  const entries = Object.entries(cart).filter(([, quantity]) => quantity > 0)
  const lines = entries.map(([sku, quantity]) => ({
    sku,
    quantity,
    product: catalog?.find((product) => product.sku === sku),
  }))
  const total = lines.reduce(
    (sum, { quantity, product }) => sum + quantity * (product?.priceCents ?? 0),
    0,
  )
  async function finish() {
    if (busy) return
    setProblem('')
    setBusy(true)
    const items = entries
      .map(([sku, quantity]) => ({ sku, quantity }))
      .sort((a, b) => a.sku.localeCompare(b.sku))
    const signature = JSON.stringify(items)
    const prior = readStored<{ signature: string; key: string } | null>(
      'checkout',
      null,
    )
    const key = prior?.signature === signature ? prior.key : crypto.randomUUID()
    saveStored('checkout', { signature, key })
    try {
      const order = await api.checkout(items, key, session.csrfToken)
      clear()
      saveStored('checkout', null)
      reload()
      navigate(paths.order(order.id))
    } catch (problem) {
      if (
        problem instanceof ApiError &&
        problem.code === 'INSUFFICIENT_STOCK'
      ) {
        setProblem(
          'Estoque insuficiente para concluir o pedido. ' +
            problem.message +
            ' Ajuste o carrinho e tente novamente.',
        )
      } else setProblem(errorMessage(problem))
      reload()
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="wrap page">
      <Link className="back" href={paths.shop}>
        <ArrowLeft size={16} />
        Voltar à loja
      </Link>
      <div className="page-heading">
        <span className="eyebrow">
          <ShoppingBag size={15} /> SUA COMPRA
        </span>
        <h1>
          Seu carrinho<span>.</span>
        </h1>
      </div>
      {error && <Notice retry={reload}>{error}</Notice>}
      {!catalog && !error && <Loading>Carregando os produtos…</Loading>}
      {catalog &&
        (entries.length === 0 ? (
          <Empty icon={<ShoppingBag />} title="Seu carrinho está vazio">
            <p>Escolha um café ou utensílio para começar seu pedido.</p>
            <Link className="button primary" href={paths.shop}>
              Explorar a loja <ArrowRight size={16} />
            </Link>
          </Empty>
        ) : (
          <div className="split">
            <div className="panel">
              <h2>Itens selecionados</h2>
              {lines.map(({ sku, quantity, product }) => (
                <div className="cart-line" key={sku}>
                  {product && <ProductImage product={product} />}
                  <div className="line-info">
                    <h3>{product?.name ?? sku}</h3>
                    <span>
                      {product
                        ? money(product.priceCents) + ' / un.'
                        : 'Produto indisponível'}
                    </span>
                    {product && quantity > product.available && (
                      <small className="warning">
                        Saldo atual: {product.available}
                      </small>
                    )}
                    <div className="quantity">
                      <button
                        disabled={busy || quantity <= 1}
                        aria-label={
                          'Diminuir quantidade de ' + (product?.name ?? sku)
                        }
                        onClick={() => change(sku, quantity - 1)}
                      >
                        <Minus size={13} />
                      </button>
                      <span>{quantity}</span>
                      <button
                        disabled={
                          busy || !product || quantity >= product.available
                        }
                        aria-label={
                          'Aumentar quantidade de ' + (product?.name ?? sku)
                        }
                        onClick={() => change(sku, quantity + 1)}
                      >
                        <Plus size={13} />
                      </button>
                    </div>
                  </div>
                  <div className="line-end">
                    <strong>
                      {product ? money(product.priceCents * quantity) : '—'}
                    </strong>
                    <button
                      className="icon-btn"
                      disabled={busy}
                      aria-label={'Remover ' + (product?.name ?? sku)}
                      onClick={() => change(sku, 0)}
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
            <aside className="panel summary">
              <Leaf size={23} />
              <h2>Resumo do pedido</h2>
              <div className="total">
                <span>Total</span>
                <strong>{money(total)}</strong>
              </div>
              <p>
                Ao finalizar, os itens ficam reservados até o prazo indicado no
                pedido. O pagamento é simulado.
              </p>
              {problem && <Notice>{problem}</Notice>}
              <button
                className="button primary full"
                disabled={busy || lines.some((line) => !line.product)}
                onClick={() => void finish()}
              >
                {busy ? 'Reservando…' : 'Finalizar pedido'}
                <ArrowRight size={16} />
              </button>
            </aside>
          </div>
        ))}
    </section>
  )
}

export function Footer() {
  return (
    <footer>
      <div className="wrap footer-inner">
        <span className="footer-name">
          <Coffee size={17} />
          Casa Torra
        </span>
        <span>Loja demonstrativa · Pagamentos simulados</span>
      </div>
    </footer>
  )
}
