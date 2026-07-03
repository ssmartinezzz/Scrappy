// shadcn-style CategoryCard primitive — a category banner card (image on
// top, content block below), adapted from a reference React/TSX design to
// this project's JSX + design-token conventions (same rewrite pattern as
// ui/button.jsx, ui/card.jsx). Renders a category banner (image, title,
// count badge, tagline) for PicksPanel's CategoryBanner — NOT a per-product
// card; see the domain ProductCard in components/ProductCard.jsx for that.
//
// Deviates from the reference on purpose:
// - No <a href>: consumers select via onClick (categories don't have a route,
//   see PicksPanel ADR-2), so the whole card is a keyboard-operable
//   role="button" instead of an anchor.
// - No bookmark/save affordance: favorites in this app are per-product
//   (url/sitio/nombre); there is no category-favorite backend, so the
//   reference's hover bookmark button is omitted rather than left dead.
// - `children` slot renders below the title/subtitle so callers can compose
//   extra content (e.g. a price line) without this primitive knowing about
//   domain fields like price/pack pricing.
//
// Composes ui/card.jsx's Card as the root element instead of re-declaring
// the surface classes (bg-s2/border/rounded-card/text-t1) — one source of
// truth for the card surface; this primitive only layers the interactive
// (hover/focus/cursor) classes on top. Card forwards ref, spreads props
// (so data-* attrs used by scroll-spy land on the DOM root), and imposes no
// padding/child structure, so it doesn't fight the edge-to-edge image.
import * as React from 'react';
import { ShoppingBag } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Card } from './card';
import { ImageWithFallback } from './image-with-fallback';

const CategoryCard = React.forwardRef(({
  className,
  imageUrl,
  title,
  subtitle,
  count,
  onClick,
  children,
  ...props
}, ref) => {
  function handleKeyDown(e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onClick?.(e);
    }
  }

  return (
    <Card
      ref={ref}
      role="button"
      tabIndex={0}
      aria-label={title}
      onClick={onClick}
      onKeyDown={handleKeyDown}
      className={cn(
        'group relative cursor-pointer overflow-hidden transition-shadow duration-300 ease-in-out hover:shadow-lg',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
        className
      )}
      {...props}
    >
      <div className="relative aspect-square overflow-hidden bg-s3">
        <ImageWithFallback
          src={imageUrl}
          alt={title}
          loading="lazy"
          className="h-full w-full object-cover transition-transform duration-300 ease-in-out group-hover:scale-105"
          fallbackClassName="flex h-full w-full items-center justify-center text-t4"
          fallback={<ShoppingBag aria-hidden="true" className="h-10 w-10" strokeWidth={1.5} />}
        />
        {/* Opacity modifiers (bg-s1/80) emit no CSS with this config's plain
            var() color tokens, so the badge uses explicit rgba values. */}
        {count != null && (
          <span className="absolute right-3 top-3 rounded-full bg-[rgba(31,27,22,0.55)] px-2 py-0.5 text-xs font-bold text-[#F5F0E6] shadow-sm backdrop-blur-sm">
            {count.toLocaleString('es-AR')}
          </span>
        )}
      </div>
      <div className="p-4">
        <h3 className="truncate font-semibold leading-tight text-t1">{title}</h3>
        {subtitle && <p className="mt-1 line-clamp-2 text-sm text-t3">{subtitle}</p>}
        {children}
      </div>
    </Card>
  );
});
CategoryCard.displayName = 'CategoryCard';

export { CategoryCard };
