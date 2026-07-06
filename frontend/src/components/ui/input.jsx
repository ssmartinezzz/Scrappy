// shadcn/ui Input primitive — copied in as foundation for Topbar/DetailPanel
// migration. Not wired into any component in this PR; wiring lands in PR4.
import * as React from 'react';
import { cn } from '@/lib/utils';

const Input = React.forwardRef(({ className, type = 'text', ...props }, ref) => (
  <input
    ref={ref}
    type={type}
    className={cn(
      // text-base (16px) on mobile prevents iOS Safari's auto-zoom on focus; text-sm from sm: up.
      'flex h-9 w-full rounded-btn border border-bd2 bg-s2 px-3 py-1 text-base text-t1 outline-none transition-colors placeholder:text-t4 focus:border-primary disabled:opacity-40 sm:text-sm',
      className
    )}
    {...props}
  />
));
Input.displayName = 'Input';

export { Input };
