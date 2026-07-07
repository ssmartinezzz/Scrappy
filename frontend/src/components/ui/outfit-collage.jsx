// Simple collage visual for an outfit-kind carousel slide (design ADR-2,
// tasks Phase 1.3). A saved outfit's slide must be visually distinct from a
// single-product slide via STRUCTURE, not color alone (ui-ux-pro-max
// visual-hierarchy) — this renders a plain image grid of the outfit's member
// photos instead of one photo.
//
// Deliberately simple for this PR: a richer overlapping mosaic is the one
// deferrable item (design ADR-12 / tasks Phase 5) and can replace these
// internals later without changing the `members` prop contract.
import { ShoppingBag } from 'lucide-react';
import { ImageWithFallback } from './image-with-fallback';

const MAX_TILES = 4;

export function OutfitCollage({ members, dimmed }) {
  const tiles = (members || []).slice(0, MAX_TILES);
  const cols = tiles.length >= 2 ? 2 : 1;

  return (
    <div
      className="grid h-full w-full gap-[2px] bg-s3"
      style={{ gridTemplateColumns: `repeat(${cols}, 1fr)`, opacity: dimmed ? 0.5 : 1 }}
    >
      {tiles.length === 0 && (
        <div className="flex h-full w-full items-center justify-center text-t4">
          <ShoppingBag aria-hidden="true" className="h-10 w-10" strokeWidth={1.5} />
        </div>
      )}
      {tiles.map((m, i) => (
        <ImageWithFallback
          key={i}
          src={m.img}
          alt={m.nombre || 'Prenda del outfit'}
          loading="lazy"
          className="h-full w-full object-cover"
          fallbackClassName="flex h-full w-full items-center justify-center bg-s3 text-t4"
          fallback={<ShoppingBag aria-hidden="true" className="h-6 w-6" strokeWidth={1.5} />}
        />
      ))}
    </div>
  );
}
