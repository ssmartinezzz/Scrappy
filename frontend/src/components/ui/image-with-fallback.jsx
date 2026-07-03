// Shared img+fallback pattern — extracted from the duplicated onError
// hide-img/show-placeholder hack that used to live independently in
// ui/category-card.jsx and the domain ProductCard.jsx. Renders the <img>
// only when `src` is truthy; on load error (or when there's no src to begin
// with) it hides the <img> and reveals the adjacent fallback placeholder.
// The fallback is toggled via inline display style, not a `hidden` class:
// tailwind-merge treats `hidden` and `flex` as conflicting display utilities
// and strips `flex` from the merged className, which broke the revealed
// placeholder's centering.
import * as React from 'react';

function ImageWithFallback({ src, alt, className, fallback, fallbackClassName, ...imgProps }) {
  return (
    <>
      {src && (
        <img
          src={src}
          alt={alt}
          className={className}
          onError={e => {
            e.target.style.display = 'none';
            const fb = e.target.nextSibling;
            if (fb) fb.style.display = '';
          }}
          {...imgProps}
        />
      )}
      <div className={fallbackClassName} style={src ? { display: 'none' } : undefined}>
        {fallback}
      </div>
    </>
  );
}

export { ImageWithFallback };
