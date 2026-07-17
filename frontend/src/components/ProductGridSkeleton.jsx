// Placeholder grid shown while the next infinite-scroll page is in flight
// (ADR-5). Reserves the same footprint as a real ProductCard — aspect-[3/4]
// image block + text-line bars — so swapping in real cards causes no
// layout shift (CLS). Purely presentational, no props/state.
const PLACEHOLDER_COUNT = 8;

export default function ProductGridSkeleton() {
  return (
    <div className="grid grid-skeleton" aria-hidden="true">
      {Array.from({ length: PLACEHOLDER_COUNT }, (_, i) => (
        <div className="card grid-skeleton-card" key={i}>
          <div className="grid-skeleton-img aspect-[3/4]" />
          <div className="grid-skeleton-body">
            <div className="grid-skeleton-line grid-skeleton-line--title" />
            <div className="grid-skeleton-line grid-skeleton-line--sub" />
            <div className="grid-skeleton-line grid-skeleton-line--price" />
          </div>
        </div>
      ))}
    </div>
  );
}
