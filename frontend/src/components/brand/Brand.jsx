import { cn } from '@/lib/utils';

// The mark is public/favicon.svg itself, not an inline copy: the browser tab,
// the iOS home-screen icon (apple-touch-icon.png, rendered from it) and every
// view draw the same file, so the brand cannot drift between them.
const MARK_SRC = `${import.meta.env.BASE_URL}favicon.svg`;

export function BrandMark({ size = 24, className }) {
  return (
    <img src={MARK_SRC} width={size} height={size} alt="" aria-hidden="true"
         className={cn('shrink-0 select-none', className)} draggable={false} />
  );
}

export function BrandLockup({ className }) {
  return (
    <span className={cn('flex items-center gap-2', className)}>
      <BrandMark size={26} />
      {/* Below 560px the topbar keeps every pixel for the rubro strip: mark only. */}
      <span className="hidden font-display text-[1.05rem] font-extrabold tracking-[-.03em] text-t1 sm:inline">Scrappy</span>
    </span>
  );
}
