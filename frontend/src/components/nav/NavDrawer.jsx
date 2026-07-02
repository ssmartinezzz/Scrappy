// Mobile primary nav (<768px). Hamburger button opens the existing
// ui/sheet.jsx (side="left") listing all 10 destinations vertically —
// direct links first, then Explorar/Análisis/Guardados as labeled
// sections, matching desktop's grouping (nav-config.js is the single
// source of truth for both surfaces). Selecting any destination closes
// the drawer automatically via SheetClose.
import { useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '../ui/button';
import { Sheet, SheetContent, SheetClose, SheetTitle } from '../ui/sheet';
import { NAV_CONFIG, isLinkActive } from './nav-config';

function DrawerLink({ to, label, Icon, active }) {
  return (
    <SheetClose asChild>
      <NavLink
        to={to}
        className={cn(
          'flex items-center gap-3 rounded-btn px-3 py-2 text-sm font-medium transition-colors',
          active ? 'bg-s2 text-primary2' : 'text-t2 hover:bg-s2 hover:text-t1'
        )}
      >
        <Icon size={18} aria-hidden="true" />
        {label}
      </NavLink>
    </SheetClose>
  );
}

export default function NavDrawer({ className }) {
  const [open, setOpen] = useState(false);
  const location = useLocation();

  return (
    <div className={className}>
      <Sheet open={open} onOpenChange={setOpen}>
        <Button
          variant="ghost"
          size="icon"
          aria-label={open ? 'Cerrar menú' : 'Abrir menú'}
          aria-haspopup="dialog"
          aria-expanded={open}
          onClick={() => setOpen(v => !v)}
        >
          {open ? <X size={20} /> : <Menu size={20} />}
        </Button>
        <SheetContent side="left" className="flex flex-col gap-1 overflow-y-auto p-3">
          <SheetTitle className="px-3 py-2">Navegación</SheetTitle>
          {NAV_CONFIG.map(node => {
            if (node.kind === 'link') {
              return (
                <DrawerLink
                  key={node.to}
                  to={node.to}
                  label={node.label}
                  Icon={node.icon}
                  active={isLinkActive(location.pathname, node.to)}
                />
              );
            }

            return (
              <div key={node.label} className="mt-2 flex flex-col gap-1">
                <span className="px-3 py-1 text-xs font-semibold uppercase tracking-wide text-t3">
                  {node.label}
                </span>
                {node.items.map(item => (
                  <DrawerLink
                    key={item.to}
                    to={item.to}
                    label={item.label}
                    Icon={item.icon}
                    active={isLinkActive(location.pathname, item.to)}
                  />
                ))}
              </div>
            );
          })}
        </SheetContent>
      </Sheet>
    </div>
  );
}
