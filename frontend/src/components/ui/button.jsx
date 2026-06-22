// shadcn/ui Button primitive — copied in as foundation for Topbar/DetailPanel
// migration. Not wired into any component in this PR; wiring lands in PR4.
import * as React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const buttonVariants = cva(
  'inline-flex items-center justify-center whitespace-nowrap rounded-btn text-sm font-semibold transition-colors disabled:pointer-events-none disabled:opacity-40',
  {
    variants: {
      variant: {
        default: 'bg-primary text-white hover:bg-primary2',
        outline: 'border border-primary text-primary2 bg-transparent hover:bg-primary hover:text-white',
        ghost: 'border border-bd2 text-t3 bg-transparent hover:border-primary hover:text-primary2',
      },
      size: {
        default: 'h-9 px-4 py-2',
        sm: 'h-7 px-3 text-xs',
        icon: 'h-9 w-9',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  }
);

const Button = React.forwardRef(({ className, variant, size, ...props }, ref) => (
  <button ref={ref} className={cn(buttonVariants({ variant, size }), className)} {...props} />
));
Button.displayName = 'Button';

export { Button, buttonVariants };
