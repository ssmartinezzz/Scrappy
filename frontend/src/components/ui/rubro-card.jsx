// Editorial rubro card — full-bleed image, themed gradient veil and a CTA row,
// adapted from a reference React/TSX design to this project's JSX conventions
// (same rewrite pattern as ui/category-card.jsx).
//
// `themeColor` is an HSL triple ("14 62% 47%") rather than a finished color:
// the gradient, the CTA surface and the glow all derive from it via
// hsl(var(--rubro-theme) / alpha), so a rubro is re-themed in one place.
//
// Like CategoryCard it is a role="button", not an <a>: rubros are a step in
// PicksPanel's local flow, not a route.
import * as React from 'react';
import { ArrowRight } from 'lucide-react';
import { cn } from '@/lib/utils';

const RubroCard = React.forwardRef(({
  className,
  imageUrl,
  title,
  icon,
  stats,
  cta = 'Ver picks',
  themeColor,
  onClick,
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
      style={{ '--rubro-theme': themeColor }}
      className={cn('rubro-card', className)}
      {...props}
    >
      {imageUrl && (
        <div className="rubro-card-img" style={{ backgroundImage: `url("${imageUrl}")` }} />
      )}
      <div className="rubro-card-veil" />

      <div className="rubro-card-body">
        <h3 className="rubro-card-title">
          <span aria-hidden="true">{icon}</span> {title}
        </h3>
        {stats && <p className="rubro-card-stats">{stats}</p>}

        <div className="rubro-card-cta">
          <span>{cta}</span>
          <ArrowRight className="rubro-card-arrow h-4 w-4" aria-hidden="true" />
        </div>
      </div>
    </div>
  );
});
RubroCard.displayName = 'RubroCard';

export { RubroCard };
