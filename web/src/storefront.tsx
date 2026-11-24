import { useEffect, useRef, useState } from 'react'
import {
  ArrowRight,
  ArrowUpRight,
  Check,
  Gamepad2,
  Grid2X2,
  Laptop,
  Monitor,
  Plus,
  Search,
  Smartphone,
  X,
} from 'lucide-react'
import { money, type Product } from './api'
import { Empty, Link, Loading, Notice, paths, ProductImage } from './common'
import { Showcase } from './showcase'
import type { StockProps } from './shop'
import './storefront.css'

const categoryIcons = {
  Todos: Grid2X2,
  iPhones: Smartphone,
  Videogames: Gamepad2,
  Notebooks: Laptop,
  Mac: Monitor,
}
const stockLabel = (available: number) =>
  available === 0
    ? 'Esgotado'
    : available === 1
      ? 'Última unidade'
      : available + ' disponíveis'

function searchText(value: string) {
  return value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase('pt-BR')
}

function ProductSheet({
  product,
  quantity,
  add,
  close,
}: {
  product: Product
  quantity: number
  add: () => void
  close: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    const before = document.activeElement
    element?.showModal()
    const original = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      element?.close()
      document.body.style.overflow = original
      if (before instanceof HTMLElement && before.isConnected)
        before.focus({ preventScroll: true })
    }
  }, [])
  return (
    <dialog
      className="product-sheet"
      ref={dialog}
      aria-labelledby="sheet-title"
      onCancel={(e) => {
        e.preventDefault()
        close()
      }}
      onClick={(e) => {
        if (e.target === dialog.current) close()
      }}
    >
      <div className="sheet-body">
        <button
          className="sheet-close"
          aria-label="Fechar ficha técnica"
          onClick={close}
          autoFocus
        >
          <X size={19} />
        </button>
        <div className="sheet-visual">
          <ProductImage product={product} />
        </div>
        <div className="sheet-copy">
          <span className="catalog-eyebrow">
            {product.brand} / {product.category}
          </span>
          <h2 id="sheet-title">{product.name}</h2>
          <p>{product.description}</p>
          <h3>Ficha técnica</h3>
          <ul>
            {product.specs.map((spec) => (
              <li key={spec}>{spec}</li>
            ))}
          </ul>
          <div className="sheet-price">
            <strong>{money(product.priceCents)}</strong>
            <span>{stockLabel(product.available)}</span>
          </div>
          <button
            className="scene-cta"
            disabled={quantity >= product.available}
            onClick={add}
          >
            {product.available === 0
              ? 'Esgotado'
              : quantity >= product.available
                ? 'Limite de estoque atingido'
                : 'Adicionar ao carrinho'}
            <Plus size={17} />
          </button>
          {quantity > 0 && (
            <Link className="sheet-cart" href={paths.cart}>
              Ver carrinho ({quantity} {quantity === 1 ? 'unidade' : 'unidades'}{' '}
              deste item)
              <ArrowRight size={14} />
            </Link>
          )}
        </div>
      </div>
    </dialog>
  )
}

function ProductCard({
  product,
  quantity,
  add,
  inspect,
}: {
  product: Product
  quantity: number
  add: () => void
  inspect: () => void
}) {
  const node = useRef<HTMLElement>(null)
  useEffect(() => {
    const element = node.current
    if (!element || !('IntersectionObserver' in window) || !element.animate)
      return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return
        if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches)
          element.animate(
            [
              { transform: 'translateY(16px)', opacity: 0.6 },
              { transform: 'translateY(0)', opacity: 1 },
            ],
            { duration: 420, easing: 'cubic-bezier(.2,.7,.2,1)' },
          )
        observer.disconnect()
      },
      { threshold: 0.08 },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return (
    <article
      ref={node}
      className="gear-card"
      data-testid={'product-' + product.sku}
    >
      <button
        className="gear-photo"
        aria-label={'Ficha técnica de ' + product.name}
        onClick={inspect}
      >
        <ProductImage product={product} />
        <span
          className={'gear-stock' + (product.available <= 1 ? ' scarce' : '')}
        >
          <i />
          {stockLabel(product.available)}
        </span>
        <span className="gear-open" aria-hidden="true">
          <ArrowUpRight size={17} />
        </span>
      </button>
      <div className="gear-info">
        <span className="catalog-eyebrow">
          {product.brand} / {product.category}
        </span>
        <h3>
          <button onClick={inspect}>{product.name}</button>
        </h3>
        <p>{product.specs.slice(0, 2).join(' · ')}</p>
        <div className="gear-buy">
          <strong>{money(product.priceCents)}</strong>
          <button
            aria-label={'Adicionar ' + product.name + ' ao carrinho'}
            data-testid={'add-' + product.sku}
            disabled={quantity >= product.available}
            onClick={add}
          >
            {quantity > 0 ? <Check size={15} /> : <Plus size={15} />}
            {product.available === 0
              ? 'Esgotado'
              : quantity >= product.available
                ? 'No carrinho'
                : 'Adicionar'}
          </button>
        </div>
      </div>
    </article>
  )
}

export function Storefront({
  catalog,
  error,
  reload,
  cart,
  change,
}: StockProps) {
  const [category, setCategory] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])
  const products = catalog ?? []
  const categories = Array.from(
    new Set(products.map((product) => product.category)),
  )
  const terms = searchText(query.trim()).split(/\s+/).filter(Boolean)
  const visible = products.filter((product) => {
    if (category && product.category !== category) return false
    if (!terms.length) return true
    const searchable = searchText(
      [product.name, product.brand, product.category, ...product.specs].join(
        ' ',
      ),
    )
    return terms.every((term) => searchable.includes(term))
  })
  const selectedProduct = products.find((product) => product.sku === selected)
  const search = useRef<HTMLInputElement>(null)
  function add(product: Product) {
    const quantity = cart[product.sku] ?? 0
    if (quantity >= product.available) return
    change(product.sku, quantity + 1)
    setAnnouncement(product.name + ' adicionado ao carrinho.')
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setAnnouncement(''), 2700)
  }
  return (
    <>
      <Showcase products={products} inspect={(p) => setSelected(p.sku)} />
      <section
        id="catalogo"
        className="wrap collection"
        aria-labelledby="catalog-title"
      >
        <div className="collection-title">
          <h2 id="catalog-title">
            Catálogo<span aria-hidden="true">.</span>
          </h2>
          {catalog && (
            <span className="collection-count" aria-live="polite">
              {String(visible.length).padStart(2, '0')}{' '}
              {visible.length === 1 ? 'produto' : 'produtos'}
            </span>
          )}
        </div>
        {categories.length > 0 && (
          <div className="catalog-controls">
            <div
              className="gear-filters"
              role="group"
              aria-label="Categorias de produtos"
            >
              {['', ...categories].map((value) => {
                const label = value || 'Todos'
                const Icon =
                  categoryIcons[label as keyof typeof categoryIcons] ?? Grid2X2
                return (
                  <button
                    key={label}
                    onClick={() => setCategory(value)}
                    aria-pressed={category === value}
                  >
                    <Icon size={16} aria-hidden="true" />
                    {label}
                  </button>
                )
              })}
            </div>
            <label className="catalog-search">
              <Search size={17} aria-hidden="true" />
              <span className="sr-only">Buscar produtos</span>
              <input
                ref={search}
                type="search"
                placeholder="Buscar produtos"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
          </div>
        )}
        {error && <Notice retry={reload}>{error}</Notice>}
        {!catalog && !error && <Loading>Carregando o catálogo…</Loading>}
        {catalog &&
          !visible.length &&
          (category || terms.length ? (
            <Empty icon={<Search />} title="Nenhum produto encontrado">
              <button
                className="button secondary"
                onClick={() => {
                  setCategory('')
                  setQuery('')
                  search.current?.focus()
                }}
              >
                Limpar filtros
              </button>
            </Empty>
          ) : (
            <Empty title="Nenhum produto disponível">
              <p>Esta seleção está vazia no momento.</p>
            </Empty>
          ))}
        <div className="gear-grid">
          {visible.map((product) => (
            <ProductCard
              key={product.sku}
              product={product}
              quantity={cart[product.sku] ?? 0}
              add={() => add(product)}
              inspect={() => setSelected(product.sku)}
            />
          ))}
        </div>
      </section>
      {selectedProduct && (
        <ProductSheet
          product={selectedProduct}
          quantity={cart[selectedProduct.sku] ?? 0}
          add={() => add(selectedProduct)}
          close={() => setSelected(null)}
        />
      )}
      <div
        role="status"
        aria-live="polite"
        className={announcement && !selectedProduct ? 'added-toast' : 'sr-only'}
      >
        {announcement && (
          <>
            <Check size={16} aria-hidden="true" />
            {announcement}
          </>
        )}
      </div>
    </>
  )
}
