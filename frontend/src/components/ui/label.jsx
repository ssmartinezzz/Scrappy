// shadcn/ui Label primitive — plain <label>, no @radix-ui/react-label
// dependency (frontend-auth-ui design D7). Radix's Label adds only
// click-forwarding a native `htmlFor` already gives us; not worth a new
// dependency for ~10 lines.
import * as React from 'react';
import { cn } from '@/lib/utils';

const Label = React.forwardRef(({ className, ...props }, ref) => (
  <label
    ref={ref}
    className={cn('text-sm font-medium leading-none text-t2 peer-disabled:cursor-not-allowed peer-disabled:opacity-70', className)}
    {...props}
  />
));
Label.displayName = 'Label';

export { Label };
