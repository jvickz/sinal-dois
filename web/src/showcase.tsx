import { useRef, useState, type PointerEvent } from 'react'
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUpRight } from 'lucide-react'
import type { Product } from './api'

const campaigns = [
  {
    sku: 'IPHONE-15-128',
    image: 'hero',
    label: 'iPhone 15',
    deck: 'Câmera de 48 MP, chip A16 Bionic e um acabamento em vidro preto.',
  },
  {
    sku: 'PS5-SLIM',
    image: 'hero-ps5',
    label: 'PS5 Slim',
    deck: 'Jogos em disco, SSD de 1 TB e a resposta tátil do controle DualSense.',
  },
  {
    sku: 'MACBOOK-AIR-M3',
    image: 'hero-macbook',
    label: 'MacBook Air',
    deck: 'Chip M3, tela Liquid Retina de 13 polegadas e um design sem ventoinha.',
  },
]

export function Showcase({
  products,
  inspect,
}: {
  products: Product[]
  inspect: (product: Product) => void
}) {
  const [index, setIndex] = useState(0)
  const swipe = useRef<{ id: number; x: number; y: number } | null>(null)
  const stage = useRef<HTMLElement>(null)
  const slides = campaigns.flatMap((campaign) => {
    const product = products.find((item) => item.sku === campaign.sku)
    return product ? [{ ...campaign, product }] : []
  })
  if (!slides.length) return null
  const current = index % slides.length
  const slide = slides[current]
  const move = (step: number) =>
    setIndex((value) => (value + step + slides.length) % slides.length)

  function light(event: PointerEvent<HTMLElement>) {
    if (
      event.pointerType !== 'mouse' ||
      !window.matchMedia(
        '(hover: hover) and (prefers-reduced-motion: no-preference)',
      ).matches
    )
      return
    const bounds = event.currentTarget.getBoundingClientRect()
    const x = (event.clientX - bounds.left) / bounds.width
    const y = (event.clientY - bounds.top) / bounds.height
    const style = event.currentTarget.style
    style.setProperty('--light-x', (x * 100).toFixed(1) + '%')
    style.setProperty('--light-y', (y * 100).toFixed(1) + '%')
    style.setProperty('--drift-x', ((x - 0.5) * 9).toFixed(1) + 'px')
    style.setProperty('--drift-y', ((y - 0.5) * 6).toFixed(1) + 'px')
  }

  return (
    <section
      className="showcase"
      ref={stage}
      tabIndex={0}
      data-testid="showcase"
      aria-roledescription="carrossel"
      aria-label="Destaques da loja"
      onKeyDown={(event) => {
        if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
        event.preventDefault()
        if ((event.target as Element).closest('.scene')) {
          event.currentTarget.focus({ preventScroll: true })
        }
        move(event.key === 'ArrowRight' ? 1 : -1)
      }}
      onPointerDown={(event) => {
        if (
          !event.isPrimary ||
          event.button !== 0 ||
          (event.target as Element).closest('a,button')
        )
          return
        event.currentTarget.setPointerCapture(event.pointerId)
        swipe.current = {
          id: event.pointerId,
          x: event.clientX,
          y: event.clientY,
        }
      }}
      onPointerCancel={() => {
        swipe.current = null
      }}
      onPointerUp={(event) => {
        const start = swipe.current
        swipe.current = null
        if (!start || start.id !== event.pointerId) return
        const dx = event.clientX - start.x
        const dy = event.clientY - start.y
        if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy) * 1.4)
          move(dx < 0 ? 1 : -1)
      }}
      onPointerMove={light}
      onPointerLeave={() => {
        stage.current?.style.removeProperty('--drift-x')
        stage.current?.style.removeProperty('--drift-y')
      }}
    >
      <div className="scene" key={slide.sku}>
        <div className="scene-drift">
          <img
            className="scene-photo"
            src={'/images/' + slide.image + '.webp'}
            alt=""
            fetchPriority="high"
            draggable={false}
          />
        </div>
        <div className="scene-light" aria-hidden="true" />
        <div className="wrap scene-inner">
          <div className="scene-copy">
            <p className="scene-brand">
              <i aria-hidden="true" />
              {slide.product.brand}
              <span>/</span>
              {slide.product.category}
            </p>
            <h1>{slide.product.name}</h1>
            <p className="scene-deck">{slide.deck}</p>
            <div className="scene-actions">
              <button
                className="scene-cta"
                onClick={() => inspect(slide.product)}
              >
                Ver produto <ArrowUpRight size={18} />
              </button>
              <a className="scene-browse" href="#catalogo">
                Explorar catálogo <ArrowDown size={15} />
              </a>
            </div>
          </div>
        </div>
      </div>
      <span className="sr-only" aria-live="polite" aria-atomic="true">
        Destaque {current + 1} de {slides.length}: {slide.product.name}
      </span>
      <div className="scene-controls">
        <div className="wrap scene-controls-inner">
          <span className="scene-counter" aria-hidden="true">
            <b>{String(current + 1).padStart(2, '0')}</b> /{' '}
            {String(slides.length).padStart(2, '0')}
          </span>
          <div
            className="scene-tabs"
            role="group"
            aria-label="Selecionar destaque"
          >
            {slides.map((item, i) => (
              <button
                key={item.sku}
                onClick={() => setIndex(i)}
                aria-pressed={i === current}
                aria-label={'Destacar ' + item.product.name}
              >
                <span className="scene-track" aria-hidden="true" />
                <span>{item.label}</span>
              </button>
            ))}
          </div>
          <div className="scene-arrows">
            <button aria-label="Destaque anterior" onClick={() => move(-1)}>
              <ArrowLeft size={17} />
            </button>
            <button aria-label="Próximo destaque" onClick={() => move(1)}>
              <ArrowRight size={17} />
            </button>
          </div>
        </div>
      </div>
    </section>
  )
}
