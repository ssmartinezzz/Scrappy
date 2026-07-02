// Desktop primary nav (>=768px). Renders a Radix Menubar row from
// nav-config.js: direct-link destinations (Catálogo, Picks, Para ti) render
// as plain NavLinks placed directly inside Menubar.Root — NOT wrapped in an
// empty MenubarMenu/MenubarTrigger pair, which would toggle data-state=open
// on click with no content and confuse users (see design Decision 1).
// Grouped destinations (Explorar, Análisis, Guardados) render as
// MenubarMenu > MenubarTrigger > MenubarContent > MenubarItem(asChild NavLink).
// No custom keydown handling — Radix Menubar owns Arrow/Enter/Escape/Home/End.
import { NavLink, useLocation } from 'react-router-dom';
import { cn } from '@/lib/utils';
import {
  Menubar, MenubarMenu, MenubarTrigger, MenubarContent, MenubarItem, MenubarLabel,
} from '../ui/menubar';
import { NAV_CONFIG, isMenuActive } from './nav-config';

// Mirrors MenubarTrigger's visual language (px-3 py-1.5 text-sm rounded-btn)
// so direct links and grouped triggers read as one consistent row, without
// requiring the plain NavLink to participate in Radix's internal context.
const directLinkClass = ({ isActive }) =>
  cn(
    'flex cursor-pointer select-none items-center gap-1.5 rounded-btn px-3 py-1.5 text-sm font-medium outline-none transition-colors',
    'hover:bg-s2 hover:text-primary2',
    isActive && 'text-primary2'
  );

export default function NavMenubar({ className }) {
  const location = useLocation();

  return (
    <Menubar className={cn('h-auto border-none bg-transparent p-0', className)}>
      {NAV_CONFIG.map(node => {
        if (node.kind === 'link') {
          const Icon = node.icon;
          return (
            <NavLink key={node.to} to={node.to} className={directLinkClass}>
              <Icon size={16} aria-hidden="true" />
              {node.label}
            </NavLink>
          );
        }

        const active = isMenuActive(location.pathname, node.items);
        const Icon = node.icon;
        return (
          <MenubarMenu key={node.label}>
            <MenubarTrigger data-active={active ? 'true' : undefined} className="gap-1.5">
              <Icon size={16} aria-hidden="true" />
              {node.label}
            </MenubarTrigger>
            <MenubarContent>
              <MenubarLabel>{node.label}</MenubarLabel>
              {node.items.map(item => {
                const ItemIcon = item.icon;
                return (
                  <MenubarItem key={item.to} asChild>
                    <NavLink
                      to={item.to}
                      className={({ isActive }) =>
                        cn('flex items-center gap-2', isActive && 'text-primary2')
                      }
                    >
                      <ItemIcon size={16} aria-hidden="true" />
                      {item.label}
                    </NavLink>
                  </MenubarItem>
                );
              })}
            </MenubarContent>
          </MenubarMenu>
        );
      })}
    </Menubar>
  );
}
