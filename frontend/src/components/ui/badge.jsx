// shadcn/ui Badge primitive — ported to JSX + project tokens. Static status
// pill (no hover states; used as a non-interactive indicator). Slash-opacity
// modifiers (bg-primary/80) emit nothing against this config's var() tokens,
// so variants use solid token backgrounds or color-mix.
import * as React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
  {
    variants: {
      variant: {
        default:     'border-transparent bg-primary text-white',
        secondary:   'border-transparent bg-s3 text-t2',
        destructive: 'border-transparent bg-danger text-white',
        success:     'border-transparent text-success',
        warning:     'border-transparent text-warning',
        outline:     'text-t2',
      },
    },
    defaultVariants: { variant: 'default' },
  }
);

function Badge({ className, variant, ...props }) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
