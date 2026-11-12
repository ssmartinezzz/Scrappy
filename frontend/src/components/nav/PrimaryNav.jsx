// Shared root of the primary nav. Forwards a single ref so AppLayout's
// ResizeObserver (tabbarRef) keeps measuring the actual rendered row.
// One row for every width: the Menubar row is the nav on desktop AND on a
// phone, where it scrolls sideways under the thumb instead of hiding behind a
// lone hamburger. The drawer trigger stays below md as the full vertical list.
// Both are mounted at every width (CSS visibility only), so nothing remounts
// on resize (see sdd/nav-menubar-redesign design Decision 3).
import { forwardRef, useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import NavMenubar from './NavMenubar';
import NavDrawer from './NavDrawer';

const PrimaryNav = forwardRef((props, ref) => {
  const { pathname } = useLocation();
  const scrollRef = useRef(null);

  // On a phone the active destination can sit past the right edge of the row.
  useEffect(() => {
    const activo = scrollRef.current?.querySelector('[aria-current="page"], [data-active="true"]');
    activo?.scrollIntoView?.({ block: 'nearest', inline: 'center' });
  }, [pathname]);

  return (
    <nav className="primary-nav" aria-label="Principal" ref={ref}>
      <NavDrawer className="shrink-0 md:hidden" />
      <div className="primary-nav-scroll" ref={scrollRef}>
        <NavMenubar />
      </div>
    </nav>
  );
});
PrimaryNav.displayName = 'PrimaryNav';

export default PrimaryNav;
