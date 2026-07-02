// Shared root of the primary nav. Forwards a single ref so AppLayout's
// existing ResizeObserver (tabbarRef) keeps measuring the actual rendered
// row — desktop Menubar or mobile hamburger bar — without any change to its
// contract (see sdd/nav-menubar-redesign design Decision 2).
// Desktop/mobile swap is pure CSS visibility (hidden/md:hidden), not a JS
// matchMedia listener: both branches stay mounted, only one is
// display-visible, so there is no dual-focus and no remount on resize
// (design Decision 3).
import { forwardRef } from 'react';
import NavMenubar from './NavMenubar';
import NavDrawer from './NavDrawer';

const PrimaryNav = forwardRef((props, ref) => (
  <div className="primary-nav" ref={ref}>
    <NavMenubar className="hidden md:flex" />
    <NavDrawer className="md:hidden" />
  </div>
));
PrimaryNav.displayName = 'PrimaryNav';

export default PrimaryNav;
