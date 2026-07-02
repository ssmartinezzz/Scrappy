import { useCallback, useEffect, useState } from 'react';
import { Card, CardHeader, CardDescription, CardContent } from './ui/card';

// Generic, category-agnostic carousel (ADR-4/5/6/7 in the design doc).
// Hybrid responsive strategy: JS items-per-view + translateX paging on
// tablet/desktop (matchMedia ≥1024px→3, 640-1023px→2), CSS scroll-snap swipe
// track on mobile (<640px, native touch semantics, no transform, no chevrons).
// renderItem keeps this reusable beyond PickCard for the planned per-category
// series (design doc "Reusability contract").
//
// Shell is the shadcn-token Card from ./ui/card (ADR-6): Card is the outer
// frame, CardHeader holds the subtitle + chevron controls, CardContent holds
// the viewport/track. CardTitle is intentionally NOT wired here — `title`
// stays an accessible name only (aria-label on the region below), because
// every current caller (CategoryPicksView) already renders the category name
// as its own <h2> right above the carousel; rendering CardTitle too would
// duplicate that heading. CardTitle stays exported from ui/card for a future
// consumer that has no heading of its own.
const DESKTOP_QUERY = '(min-width: 1024px)';
const TABLET_MIN_QUERY = '(min-width: 640px)';

function computeItemsPerView() {
  if (typeof window === 'undefined') return 3;
  if (window.matchMedia(DESKTOP_QUERY).matches) return 3;
  if (window.matchMedia(TABLET_MIN_QUERY).matches) return 2;
  return 1; // mobile: informational only, real layout comes from scroll-snap CSS
}

function computeIsMobile() {
  return typeof window !== 'undefined' && !window.matchMedia(TABLET_MIN_QUERY).matches;
}

export default function CategoryPicksCarousel({ title, subtitle, items, renderItem, className }) {
  const [itemsPerView, setItemsPerView] = useState(computeItemsPerView);
  const [mobile, setMobile] = useState(computeIsMobile);
  const [page, setPage] = useState(0);

  // Track breakpoint changes — recompute itemsPerView + mobile mode together
  // so the transform/scroll-snap toggle and the page-count math stay in sync.
  useEffect(() => {
    const desktopMq = window.matchMedia(DESKTOP_QUERY);
    const tabletMinMq = window.matchMedia(TABLET_MIN_QUERY);
    const onChange = () => {
      setItemsPerView(computeItemsPerView());
      setMobile(computeIsMobile());
    };
    desktopMq.addEventListener('change', onChange);
    tabletMinMq.addEventListener('change', onChange);
    return () => {
      desktopMq.removeEventListener('change', onChange);
      tabletMinMq.removeEventListener('change', onChange);
    };
  }, []);

  const safeItems = items || [];
  const pageCount = Math.max(1, Math.ceil(safeItems.length / itemsPerView));

  // Clamp page when itemsPerView changes (e.g. resizing from desktop to tablet)
  useEffect(() => {
    setPage(p => Math.min(p, pageCount - 1));
  }, [pageCount]);

  const canPrev = page > 0;
  const canNext = page < pageCount - 1;

  const goPrev = useCallback(() => setPage(p => (p > 0 ? p - 1 : p)), []);
  const goNext = useCallback(() => setPage(p => (p < pageCount - 1 ? p + 1 : p)), [pageCount]);

  const handleKeyDown = useCallback(e => {
    if (mobile) return; // mobile uses native swipe/scroll, not the transform track
    if (e.key === 'ArrowLeft') { e.preventDefault(); goPrev(); }
    if (e.key === 'ArrowRight') { e.preventDefault(); goNext(); }
  }, [mobile, goPrev, goNext]);

  const showChevrons = !mobile && safeItems.length > itemsPerView;

  const trackStyle = mobile
    ? undefined
    : {
        '--per-view': itemsPerView,
        '--gap': '16px',
        transform: `translateX(calc(-1 * ${page} * 100%))`,
      };

  const showHeader = Boolean(subtitle) || showChevrons;

  return (
    <Card className={`picks-carousel${className ? ` ${className}` : ''}`}>
      {showHeader && (
        <CardHeader className="picks-carousel-header">
          <div className="picks-carousel-headrow">
            {subtitle && (
              <CardDescription className="picks-carousel-subtitle">{subtitle}</CardDescription>
            )}
            {showChevrons && (
              <div className="picks-carousel-chevrons">
                <button type="button" className="picks-carousel-chevron picks-carousel-chevron-prev"
                  aria-label="Previous" disabled={!canPrev} onClick={goPrev}>
                  <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
                    <path d="M15 18l-6-6 6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </button>
                <button type="button" className="picks-carousel-chevron picks-carousel-chevron-next"
                  aria-label="Next" disabled={!canNext} onClick={goNext}>
                  <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
                    <path d="M9 18l6-6-6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </button>
              </div>
            )}
          </div>
        </CardHeader>
      )}

      <CardContent className="picks-carousel-content">
        <div
          className={`picks-carousel-viewport${mobile ? ' picks-carousel-viewport-snap' : ''}`}
          role="region"
          aria-roledescription="carousel"
          aria-label={title}
          tabIndex={0}
          onKeyDown={handleKeyDown}
        >
          <div className="picks-carousel-track" style={trackStyle}>
            {safeItems.map((item, i) => (
              <div key={i} className="picks-carousel-item">
                {renderItem(item, i)}
              </div>
            ))}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
