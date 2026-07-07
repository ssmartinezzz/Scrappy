// Generic 3D-tilt carousel primitive — adapted from a user-supplied
// Aceternity-style TSX component (verbatim source: engram
// sdd/favoritos-carousel-view/carousel-prompt). Ported TSX -> JSX (no
// `interface`/generics, project has no TypeScript), dropped `"use client"`
// (Vite SPA, not a Next.js app), and swapped `@tabler/icons-react` for
// `lucide-react` (`ArrowRight`) to keep a single icon family across the app
// (ui-ux-pro-max style priority-4 no-emoji-icons/icon-style-consistent —
// tabler isn't installed and the rest of the app is 100% lucide, e.g.
// cron/CronJobCard.jsx, ui/category-card.jsx).
//
// The source's hand-rolled requestAnimationFrame + CSS-var mouse-parallax
// loop is replaced with framer-motion useMotionValue/useSpring (already a
// dependency, see cron/CronJobCard.jsx for the same motion + useReducedMotion
// pattern in this repo) gated by useReducedMotion() — the source component
// had ZERO reduced-motion handling (design ADR-5, ui-ux-pro-max Accessibility
// priority-1 `reduced-motion`).
//
// Dumb/generic: this file knows nothing about favorites/outfits. It renders
// a `slides` array and calls `slide.onActivate(slide)` when the ALREADY
// active slide is activated again (click / Enter / Space). Consumers pick
// the discriminated shape (design ADR-2):
//   Product slide: { kind:'product', id, title, cta, image, descontinuado, onActivate }
//   Outfit slide:  { kind:'outfit',  id, title, cta, members:[{nombre,img,...}],
//                    expanded, controlsId, onActivate }
//     `expanded`/`controlsId` are consumer-owned (e.g. FavoritosPanel's
//     inline member-strip state) and only wired to aria-expanded/
//     aria-controls on the slide trigger when `kind === 'outfit'`.
import { useEffect, useId, useRef, useState } from 'react';
import { motion, useMotionValue, useReducedMotion, useSpring } from 'framer-motion';
import { ArrowRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ImageWithFallback } from './image-with-fallback';
import { OutfitCollage } from './outfit-collage';

// Slide sizing clamp (design ADR-7): never a raw 70vmin. Bounded by both a
// viewport-relative size AND the app's reserved sticky chrome (--sticky-offset,
// set in styles.css :root, shared by every route) plus a fixed allowance for
// this panel's own sticky header + the carousel's own below-slide controls,
// so the hero never overflows under the topbar/tabbar chrome.
const SLIDE_SIZE_CSS = 'max(220px, min(70vmin, calc(100dvh - var(--sticky-offset) - 220px)))';

// Space reserved below the slide box for the prev/next controls (their own
// `top: calc(100% + 1rem)` offset + control height + a little breathing room).
const CONTROLS_ALLOWANCE_PX = 96;

// Bounded touch-swipe threshold (design ADR-6 drag-threshold) — small taps
// still register as activation clicks; only a real horizontal drag pages.
const SWIPE_THRESHOLD_PX = 50;

function Slide({ slide, index, current, onSlideActivate }) {
  const slideRef = useRef(null);
  const reduceMotion = useReducedMotion();
  const isActive = current === index;

  // Tilt/parallax bound to this slide's own motion values; only wired to
  // pointer input when this slide is active AND motion isn't reduced AND the
  // pointer is a mouse (parallax is a pointer:fine enhancement, ADR-6 — never
  // required for touch/coarse pointers).
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const springX = useSpring(x, { stiffness: 150, damping: 20, mass: 0.5 });
  const springY = useSpring(y, { stiffness: 150, damping: 20, mass: 0.5 });

  function handlePointerMove(e) {
    if (reduceMotion || e.pointerType !== 'mouse' || !isActive) return;
    const el = slideRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    x.set((e.clientX - (r.left + r.width / 2)) / 30);
    y.set((e.clientY - (r.top + r.height / 2)) / 30);
  }

  function handlePointerLeave() {
    x.set(0);
    y.set(0);
  }

  // e.detail === 0 identifies a click synthesized from a keyboard activation
  // (Enter/Space) on a real <button> — real mouse/touch clicks report
  // detail >= 1. This lets a single keyboard activation both center AND open
  // the slide in one press, while mouse/touch clicks keep the two-step
  // "click to bring forward, click again (now centered) to open" coverflow
  // affordance (spec fix: "single Enter/Space on a focused slide activates it").
  function handleClick(e) {
    onSlideActivate(index, { viaKeyboard: e.detail === 0 });
  }

  const { kind, title, image, descontinuado, members, cta, expanded, controlsId } = slide;

  // Reduced-motion (spec fix): the settle scale/rotateX tilt is now gated
  // too — previously only pointer-parallax and the track transition were
  // gated, leaving this transform/transition active under reduced motion.
  // With reduced motion, inactive slides skip the 3D rotateX tilt entirely
  // and the transform change is instant (no transition).
  const settleTransform = isActive
    ? 'scale(1) rotateX(0deg)'
    : reduceMotion ? 'scale(1)' : 'scale(0.98) rotateX(8deg)';
  const settleTransition = reduceMotion ? 'none' : 'transform 0.5s cubic-bezier(0.4, 0, 0.2, 1)';

  // aria-expanded/aria-controls (spec fix): only meaningful for an outfit
  // slide, whose activation reveals an inline member strip owned by the
  // consumer (FavoritosPanel) — `expanded`/`controlsId` are adapter-supplied.
  const expandableProps = kind === 'outfit'
    ? { 'aria-expanded': Boolean(expanded), 'aria-controls': controlsId }
    : {};

  return (
    // Perspective/transform-style live directly on the <li> (not a wrapping
    // <div>) so the <ul>'s direct children stay <li> elements — a wrapping
    // <div> here would break list semantics (AT can't announce a proper
    // list/item count when the list's children aren't list items). A <li>
    // is a valid perspective-establishing ancestor for its own rotateX'd
    // button child, so the 3D effect is unaffected.
    <li
      className="relative z-10 mx-[2vmin] flex flex-1 flex-col items-center justify-center rounded-[4px] [perspective:1200px] [transform-style:preserve-3d]"
      style={{
        width: `var(--tc-slide-size, ${SLIDE_SIZE_CSS})`,
        height: `var(--tc-slide-size, ${SLIDE_SIZE_CSS})`,
      }}
    >
      {/* Real <button>, not `role="button"` on the <li> — keeps native
          keyboard/AT semantics (default Enter/Space activation, correct
          focusability) instead of reimplementing them. */}
      <button
          ref={slideRef}
          type="button"
          tabIndex={isActive ? 0 : -1}
          aria-label={title}
          {...expandableProps}
          className={cn(
            'relative flex h-full w-full flex-col items-center justify-center rounded-[4px] border-0 bg-transparent p-0',
            'text-center text-white',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-bg'
          )}
          style={{
            transform: settleTransform,
            transition: settleTransition,
            transformOrigin: 'bottom',
          }}
          onClick={handleClick}
          onPointerMove={handlePointerMove}
          onPointerLeave={handlePointerLeave}
        >
          <motion.div
            className="absolute inset-0 overflow-hidden rounded-[4px] bg-s3"
            style={isActive && !reduceMotion ? { x: springX, y: springY } : undefined}
          >
            {kind === 'outfit' ? (
              <OutfitCollage members={members} dimmed={!isActive} />
            ) : (
              <ImageWithFallback
                src={image}
                alt={title}
                loading="eager"
                className="absolute inset-0 h-full w-full object-cover transition-opacity duration-500 ease-in-out"
                style={{ opacity: isActive ? 1 : 0.5 }}
                fallbackClassName="flex h-full w-full items-center justify-center text-t4"
                fallback={<span className="text-xs">Sin imagen</span>}
              />
            )}

            {/* Scrim (design ADR-8): the source's `bg-black/30` emits ZERO CSS
                against this project's var()-token Tailwind config (confirmed
                gotcha, ui/category-card.jsx). Inline rgba() instead, never a
                `/opacity` modifier on these tokens. 40% meets the 40-60%
                scrim-legibility guidance so the white title/CTA stay readable. */}
            {isActive && (
              <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.4)' }} aria-hidden="true" />
            )}

            {descontinuado && (
              <span
                className="absolute left-3 top-3 rounded-full px-2 py-0.5 text-xs font-bold text-white"
                style={{ background: 'rgba(192,57,43,0.9)' }}
              >
                Descontinuado
              </span>
            )}
          </motion.div>

          <article
            className={cn(
              'relative p-[4vmin] transition-opacity duration-300 ease-in-out',
              isActive ? 'visible opacity-100' : 'invisible opacity-0'
            )}
          >
            <h2
              className="relative text-lg font-semibold md:text-2xl lg:text-3xl"
              style={{ textShadow: '0 1px 4px rgba(0,0,0,0.85)' }}
            >
              {title}
            </h2>
            <div className="mt-4 flex justify-center">
              <span className="inline-flex h-11 items-center gap-1.5 rounded-2xl bg-white px-4 text-xs font-semibold text-[#1D1F2F] shadow-[0px_2px_3px_-1px_rgba(0,0,0,0.15)] sm:text-sm">
                {cta}
                <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </span>
            </div>
          </article>
      </button>
    </li>
  );
}

function CarouselControl({ direction, label, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={cn(
        'mx-2 flex h-11 w-11 items-center justify-center rounded-full border-2 border-transparent bg-s3 text-t2',
        'transition duration-200 hover:-translate-y-0.5 active:translate-y-0.5',
        'focus-visible:outline-none focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-primary',
        direction === 'previous' && 'rotate-180'
      )}
    >
      <ArrowRight className="h-5 w-5" aria-hidden="true" />
    </button>
  );
}

export function TiltCarousel({ slides, className }) {
  const [current, setCurrent] = useState(0);
  const reduceMotion = useReducedMotion();
  const id = useId();
  const dragStartXRef = useRef(null);

  const safeSlides = slides || [];
  const hasMultiple = safeSlides.length > 1;

  // `current` is local state never reconciled with `safeSlides.length` on
  // its own — if the slide count shrinks while this stays mounted (e.g. a
  // refresh drops a favorited product) and `current` points past the new
  // end, the track scrolls past the last slide and nothing is marked
  // active (blank, stuck until the user manually cycles prev/next). Snap
  // back to the first slide whenever the count shrinks under the current
  // index.
  useEffect(() => {
    if (current > safeSlides.length - 1) setCurrent(0);
  }, [safeSlides.length, current]);

  function goPrevious() {
    setCurrent(c => (c - 1 < 0 ? safeSlides.length - 1 : c - 1));
  }
  function goNext() {
    setCurrent(c => (c + 1 === safeSlides.length ? 0 : c + 1));
  }
  // Mouse/touch clicks keep the coverflow two-step (bring an off-center
  // slide forward first, click again once it's centered to open it) — that
  // preview step is a deliberate, discoverable mouse affordance. A KEYBOARD
  // activation (Enter/Space, detected via `viaKeyboard`) always both centers
  // AND opens in a single press: a keyboard user reaching a slide via Tab/
  // arrow navigation should never need two presses to activate it (spec
  // fix: no "double-Enter to activate").
  function handleSlideActivate(index, { viaKeyboard = false } = {}) {
    const wasCurrent = index === current;
    if (!wasCurrent) setCurrent(index);
    if (wasCurrent || viaKeyboard) {
      safeSlides[index]?.onActivate?.(safeSlides[index]);
    }
  }

  // Bounded horizontal swipe (design ADR-6): only touch/pen pointers page the
  // carousel; a movement threshold prevents small taps (which should count as
  // activation clicks) from being misread as a swipe, and vertical page
  // scroll is never captured (no preventDefault on the pointer events here).
  function handlePointerDown(e) {
    if (e.pointerType === 'mouse') return;
    dragStartXRef.current = e.clientX;
  }
  function handlePointerUp(e) {
    if (dragStartXRef.current == null) return;
    const delta = e.clientX - dragStartXRef.current;
    dragStartXRef.current = null;
    if (!hasMultiple) return;
    if (delta > SWIPE_THRESHOLD_PX) goPrevious();
    else if (delta < -SWIPE_THRESHOLD_PX) goNext();
  }

  if (safeSlides.length === 0) return null;

  // Two-layer structure mirrors the source component's intended usage (its
  // own demo wrapped `<Carousel>` in a full-width `overflow-hidden` div):
  // an OUTER full-width clipping region (so off-screen slides never cause
  // horizontal page scroll — ui-ux-pro-max `horizontal-scroll`) sized to fit
  // the slide box PLUS room for the below-slide controls (so they aren't
  // clipped), and an INNER slide-sized box (`--tc-slide-size`, ADR-7) that
  // owns the absolutely-positioned track — same box the original called
  // `Carousel`'s root.
  const outerHeight = hasMultiple
    ? `calc(${SLIDE_SIZE_CSS} + ${CONTROLS_ALLOWANCE_PX}px)`
    : SLIDE_SIZE_CSS;

  return (
    <div className={cn('relative w-full overflow-hidden', className)} style={{ height: outerHeight }}>
      <div
        className="tilt-carousel relative mx-auto"
        style={{ '--tc-slide-size': SLIDE_SIZE_CSS, width: 'var(--tc-slide-size)', height: 'var(--tc-slide-size)' }}
        role="region"
        aria-roledescription="carousel"
        aria-labelledby={`tilt-carousel-heading-${id}`}
      >
        <span id={`tilt-carousel-heading-${id}`} className="sr-only">Favoritos</span>
        <ul
          className="absolute flex touch-pan-y"
          style={{
            marginLeft: '-2vmin',
            marginRight: '-2vmin',
            transform: `translateX(-${current * (100 / safeSlides.length)}%)`,
            transition: reduceMotion ? 'none' : 'transform 0.6s ease-out',
          }}
          onPointerDown={handlePointerDown}
          onPointerUp={handlePointerUp}
        >
          {safeSlides.map((slide, index) => (
            <Slide key={slide.id ?? index} slide={slide} index={index} current={current} onSlideActivate={handleSlideActivate} />
          ))}
        </ul>

        {hasMultiple && (
          <div className="absolute top-[calc(100%+1rem)] flex w-full justify-center">
            <CarouselControl direction="previous" label="Ver favorito anterior" onClick={goPrevious} />
            <CarouselControl direction="next" label="Ver favorito siguiente" onClick={goNext} />
          </div>
        )}
      </div>
    </div>
  );
}
