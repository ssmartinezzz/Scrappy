// shadcn-style ProductCard primitive — adapted from a reference React/TSX
// design (image on top, content block below) to this project's JSX +
// design-token conventions (same rewrite pattern as ui/button.jsx, ui/card.jsx).
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
import * as React from 'react';
import { ShoppingBag } from 'lucide-react';
import { cn } from '@/lib/utils';

const ProductCard = React.forwardRef(({
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
    <div
      ref={ref}
      role="button"
      tabIndex={0}
      aria-label={title}
      onClick={onClick}
      onKeyDown={handleKeyDown}
      className={cn(
        'group relative cursor-pointer overflow-hidden rounded-card border border-border bg-s2 text-t1 transition-shadow duration-300 ease-in-out hover:shadow-lg',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
        className
      )}
      {...props}
    >
      <div className="relative aspect-square overflow-hidden bg-s3">
        {imageUrl && (
          <img
            src={imageUrl}
            alt={title}
            loading="lazy"
            className="h-full w-full object-cover transition-transform duration-300 ease-in-out group-hover:scale-105"
            onError={e => {
              e.target.style.display = 'none';
              e.target.nextSibling?.classList.remove('hidden');
            }}
          />
        )}
        <div className={cn('flex h-full w-full items-center justify-center text-t4', imageUrl && 'hidden')}>
          <ShoppingBag aria-hidden="true" className="h-10 w-10" strokeWidth={1.5} />
        </div>
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
    </div>
  );
});
ProductCard.displayName = 'ProductCard';

export { ProductCard };
