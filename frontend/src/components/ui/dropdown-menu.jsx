// shadcn/ui DropdownMenu primitive, adapted to JSX and re-themed to project
// tokens — same treatment menubar.jsx got (slate → project palette). Kept as a
// sibling of that file rather than folded into it: Radix ships Menubar and
// DropdownMenu as separate roots, and a menubar trigger is part of a roving
// tablist while a dropdown trigger stands alone.
import * as React from 'react';
import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu';
import { cn } from '@/lib/utils';

const DropdownMenu = DropdownMenuPrimitive.Root;
const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;
const DropdownMenuGroup = DropdownMenuPrimitive.Group;

const DropdownMenuContent = React.forwardRef(
  ({ className, sideOffset = 8, collisionPadding = 8, ...props }, ref) => (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        ref={ref}
        sideOffset={sideOffset}
        // Radix flips/shifts the panel to stay on screen; the padding keeps it
        // off the very edge on a narrow viewport instead of flush against it.
        collisionPadding={collisionPadding}
        className={cn(
          'z-[240] overflow-hidden rounded-card border border-border bg-s1 p-1 text-t1 shadow-lg',
          'data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          'data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2',
          'data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2',
          className
        )}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  )
);
DropdownMenuContent.displayName = DropdownMenuPrimitive.Content.displayName;

// `destructive` mirrors the upstream variant prop without pulling in cva for a
// single boolean: the danger token only changes text colour and hover ground.
const DropdownMenuItem = React.forwardRef(
  ({ className, inset, destructive, ...props }, ref) => (
    <DropdownMenuPrimitive.Item
      ref={ref}
      className={cn(
        'relative flex cursor-pointer select-none items-center gap-2 rounded-btn px-2 py-2 text-sm outline-none',
        destructive
          ? 'text-danger focus:bg-[color-mix(in_srgb,var(--r)_12%,transparent)] focus:text-danger'
          : 'focus:bg-s2 focus:text-t1',
        'data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
        inset && 'pl-8',
        className
      )}
      {...props}
    />
  )
);
DropdownMenuItem.displayName = DropdownMenuPrimitive.Item.displayName;

const DropdownMenuLabel = React.forwardRef(({ className, inset, ...props }, ref) => (
  <DropdownMenuPrimitive.Label
    ref={ref}
    className={cn('px-2 py-1.5 text-sm font-semibold text-t3', inset && 'pl-8', className)}
    {...props}
  />
));
DropdownMenuLabel.displayName = DropdownMenuPrimitive.Label.displayName;

const DropdownMenuSeparator = React.forwardRef(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Separator
    ref={ref}
    className={cn('-mx-1 my-1 h-px bg-border', className)}
    {...props}
  />
));
DropdownMenuSeparator.displayName = DropdownMenuPrimitive.Separator.displayName;

export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuGroup,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
};
