// shadcn/ui Menubar primitive, adapted to JSX and re-themed to project tokens
// (slate → project palette, see sdd/nav-menubar-redesign design Decision 5).
// Foundation for the desktop primary nav (nav/NavMenubar.jsx).
import * as React from 'react';
import * as MenubarPrimitive from '@radix-ui/react-menubar';
import { cn } from '@/lib/utils';

const MenubarMenu = MenubarPrimitive.Menu;

const Menubar = React.forwardRef(({ className, ...props }, ref) => (
  <MenubarPrimitive.Root
    ref={ref}
    className={cn(
      'flex h-10 items-center space-x-1 rounded-btn border border-border bg-s1 p-1',
      className
    )}
    {...props}
  />
));
Menubar.displayName = MenubarPrimitive.Root.displayName;

const MenubarTrigger = React.forwardRef(({ className, ...props }, ref) => (
  <MenubarPrimitive.Trigger
    ref={ref}
    className={cn(
      'flex cursor-default select-none items-center rounded-btn px-3 py-1.5 text-sm font-medium outline-none',
      'focus:bg-s2 focus:text-primary2',
      'data-[state=open]:bg-s2 data-[state=open]:text-primary2',
      'data-[active=true]:text-primary2',
      className
    )}
    {...props}
  />
));
MenubarTrigger.displayName = MenubarPrimitive.Trigger.displayName;

const MenubarContent = React.forwardRef(
  ({ className, align = 'start', alignOffset = -4, sideOffset = 8, ...props }, ref) => (
    <MenubarPrimitive.Portal>
      <MenubarPrimitive.Content
        ref={ref}
        align={align}
        alignOffset={alignOffset}
        sideOffset={sideOffset}
        className={cn(
          'z-[240] min-w-[12rem] overflow-hidden rounded-btn border border-border bg-s1 p-1 text-t1 shadow-lg',
          'data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          'data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2',
          'data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2',
          className
        )}
        {...props}
      />
    </MenubarPrimitive.Portal>
  )
);
MenubarContent.displayName = MenubarPrimitive.Content.displayName;

const MenubarItem = React.forwardRef(({ className, inset, ...props }, ref) => (
  <MenubarPrimitive.Item
    ref={ref}
    className={cn(
      'relative flex cursor-default select-none items-center rounded-btn px-2 py-1.5 text-sm outline-none',
      'focus:bg-s2 focus:text-t1',
      'data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      inset && 'pl-8',
      className
    )}
    {...props}
  />
));
MenubarItem.displayName = MenubarPrimitive.Item.displayName;

const MenubarSeparator = React.forwardRef(({ className, ...props }, ref) => (
  <MenubarPrimitive.Separator
    ref={ref}
    className={cn('-mx-1 my-1 h-px bg-border', className)}
    {...props}
  />
));
MenubarSeparator.displayName = MenubarPrimitive.Separator.displayName;

const MenubarLabel = React.forwardRef(({ className, inset, ...props }, ref) => (
  <MenubarPrimitive.Label
    ref={ref}
    className={cn('px-2 py-1.5 text-sm font-semibold text-t3', inset && 'pl-8', className)}
    {...props}
  />
));
MenubarLabel.displayName = MenubarPrimitive.Label.displayName;

export {
  Menubar,
  MenubarMenu,
  MenubarTrigger,
  MenubarContent,
  MenubarItem,
  MenubarSeparator,
  MenubarLabel,
};
