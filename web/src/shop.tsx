import { useState } from 'react'
import {
  ArrowLeft,
  ArrowRight,
  ReceiptText,
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
  Brand,
  ProductImage,
  navigate,
  paths,
  readStored,
  saveStored,
} from './common'

export { Storefront as Shop } from './storefront'

export type Cart = Record<string, number>
export interface StockProps {
  catalog: Product[] | null
  error: string
  reload: () => void
  cart: Cart
  change: (sku: string, quantity: number) => void
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
            <p>Escolha um eletrônico no catálogo para começar seu pedido.</p>
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
              <ReceiptText size={23} />
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
        <Brand />
        <span>Loja demonstrativa · Pagamentos simulados</span>
      </div>
    </footer>
  )
}
