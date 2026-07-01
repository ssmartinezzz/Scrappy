import { useEffect, useRef, useState } from 'react';

// Threshold (px) of accumulated scroll delta before flipping hidden state —
// avoids flicker on tiny/inertial scroll jitter (Spec: "Rapid direction
// changes do not cause flicker").
const SCROLL_THRESHOLD = 8;
// Always show the bar near the top of the page regardless of last direction
// (Spec: "Bar always visible at top of page").
const TOP_GUARD = 40;

/**
 * Combines scroll-direction-based hide/reveal for the Catálogo filter bar
 * with a ResizeObserver that publishes the SearchHero's rendered height as
 * the `--catalogo-hero-h` CSS custom property (scoped, Catálogo-only —
 * never touches --topbar-h/--tabbar-h/--sticky-offset).
 *
 * Usage: attach `heroRef` to the element wrapping <SearchHero/>, and use
 * `hidden` to toggle the `.catalogo-filter-bar--hidden` class.
 */
export default function useStickyFilterBar() {
  const heroRef = useRef(null);
  const [hidden, setHidden] = useState(false);
  const lastYRef = useRef(0);

  useEffect(() => {
    lastYRef.current = window.scrollY;
    let ticking = false;

    function onScroll() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        const y = window.scrollY;
        if (y <= TOP_GUARD) {
          setHidden(false);
          lastYRef.current = y;
        } else {
          const delta = y - lastYRef.current;
          if (Math.abs(delta) >= SCROLL_THRESHOLD) {
            setHidden(delta > 0); // scrolling down -> hide, scrolling up -> reveal
            lastYRef.current = y;
          }
        }
        ticking = false;
      });
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const node = heroRef.current;
    if (!node) return undefined;
    const update = () => {
      document.documentElement.style.setProperty('--catalogo-hero-h', `${node.offsetHeight}px`);
    };
    const ro = new ResizeObserver(update);
    ro.observe(node);
    update(); // initial measurement before first resize event
    return () => {
      ro.disconnect();
      document.documentElement.style.removeProperty('--catalogo-hero-h');
    };
  }, []);

  return { hidden, heroRef };
}
