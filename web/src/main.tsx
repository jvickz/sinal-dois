import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ShoppingBag } from 'lucide-react'
import '@fontsource/dm-sans/latin-400.css'
import '@fontsource/dm-sans/latin-500.css'
import '@fontsource/dm-sans/latin-600.css'
import '@fontsource/dm-sans/latin-700.css'
import { api, type Session } from './api'
import {
  Brand,
  Link,
  Loading,
  Notice,
  paths,
  readStored,
  saveStored,
  useResource,
} from './common'
import { OrderDetail, OrderHistory } from './orders'
import { Operations } from './operations'
import { CartPage, Footer, Shop, type Cart } from './shop'
import './styles.css'

function App() {
  const [search, setSearch] = useState(window.location.search)
  const params = new URLSearchParams(search)
  const orderId = params.get('pedido')
  const area = orderId ? 'pedido' : params.get('area') || 'loja'
  const session = useResource(api.session)
  const catalog = useResource(api.catalog)
  const [cart, setCart] = useState<Cart>(() => readStored('cart', {}))
  const main = useRef<HTMLElement>(null)
  const previousSearch = useRef(search)
  const previousArea = useRef(area)
  useEffect(() => {
    function changed() {
      setSearch(window.location.search)
    }
    window.addEventListener('popstate', changed)
    return () => window.removeEventListener('popstate', changed)
  }, [])
  useEffect(() => {
    if (previousSearch.current !== search) {
      previousSearch.current = search
      main.current?.focus({ preventScroll: true })
    }
  }, [search])
  useEffect(() => {
    document.title =
      ({
        loja: 'Eletrônicos',
        carrinho: 'Seu carrinho',
        pedidos: 'Meus pedidos',
        pedido: 'Seu pedido',
        operacoes: 'Operações',
      }[area] ?? 'Loja') + ' · Sinal Dois'
    if (
      previousArea.current !== area &&
      (area === 'loja' || area === 'carrinho')
    )
      catalog.reload()
    previousArea.current = area
  }, [area, catalog.reload])
  useEffect(() => {
    const refresh = () => session.reload()
    const renewed = (event: Event) =>
      session.setData((event as CustomEvent<Session>).detail)
    window.addEventListener('sinaldois:session', refresh)
    window.addEventListener('sinaldois:session-ready', renewed)
    return () => {
      window.removeEventListener('sinaldois:session', refresh)
      window.removeEventListener('sinaldois:session-ready', renewed)
    }
  }, [session.reload, session.setData])
  function updateCart(next: Cart) {
    setCart(next)
    saveStored('cart', next)
  }
  function change(sku: string, quantity: number) {
    const next = { ...cart }
    if (quantity > 0) next[sku] = quantity
    else delete next[sku]
    updateCart(next)
  }
  const stock = {
    catalog: catalog.data,
    error: catalog.error,
    reload: catalog.reload,
    cart,
    change,
  }
  const count = Object.values(cart).reduce((sum, n) => sum + n, 0)
  const nav = [
    { id: 'loja', href: paths.shop, label: 'Loja' },
    { id: 'pedidos', href: paths.orders, label: 'Meus pedidos' },
    { id: 'operacoes', href: paths.ops, label: 'Operações' },
  ]
  function renderPage() {
    if (!['carrinho', 'pedidos', 'pedido', 'operacoes'].includes(area))
      return <Shop {...stock} />
    if (!session.data)
      return session.error ? null : (
        <div className="wrap page">
          <Loading>Abrindo sua sessão…</Loading>
        </div>
      )
    switch (area) {
      case 'carrinho':
        return (
          <CartPage
            {...stock}
            session={session.data}
            clear={() => updateCart({})}
          />
        )
      case 'pedidos':
        return <OrderHistory />
      case 'pedido':
        return (
          orderId && (
            <OrderDetail key={orderId} id={orderId} session={session.data} />
          )
        )
      case 'operacoes':
        return <Operations session={session.data} update={session.setData} />
    }
  }
  return (
    <div className="site">
      <a className="skip" href="#conteudo">
        Pular para o conteúdo
      </a>
      <header className="site-header">
        <div className="wrap header-inner">
          <Brand />
          <nav aria-label="Navegação principal">
            {nav.map((item) => (
              <Link
                key={item.id}
                href={item.href}
                aria-current={
                  area === item.id ||
                  (area === 'pedido' && item.id === 'pedidos')
                    ? 'page'
                    : undefined
                }
              >
                {item.label}
              </Link>
            ))}
          </nav>
          <Link
            className="cart-link"
            href={paths.cart}
            aria-label="Carrinho"
            aria-current={area === 'carrinho' ? 'page' : undefined}
          >
            <ShoppingBag size={16} />
            <span>Carrinho</span>
            <b>{count}</b>
          </Link>
        </div>
      </header>
      <main ref={main} tabIndex={-1} id="conteudo">
        {session.error && (
          <div className="wrap">
            <Notice retry={session.reload}>{session.error}</Notice>
          </div>
        )}
        {renderPage()}
      </main>
      <Footer />
    </div>
  )
}
createRoot(document.getElementById('root')!).render(<App />)
