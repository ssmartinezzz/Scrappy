// Desktop primary nav (>=768px). Renders one visual row from nav-config.js:
// direct-link destinations (Catálogo, Picks, Para ti) are plain NavLinks in
// the flex row, and Menubar wraps ONLY the grouped destinations (Explorar,
// Análisis, Guardados). Direct links must stay OUTSIDE Menubar.Root: Radix's
// roving focus only registers MenubarTrigger items, so bare children inside
// the root are skipped by Arrow/Home/End navigation and break the ARIA
// contract (role="menubar" requires menuitem-role children). Outside the
// root they are ordinary Tab-navigable links, and Radix owns Arrow/Enter/
// Escape/Home/End within the grouped menus only (design Decision 1 fallback).
import { NavLink, useLocation } from 'react-router-dom';
import { cn } from '@/lib/utils';
import {
  Menubar, MenubarMenu, MenubarTrigger, MenubarContent, MenubarItem, MenubarLabel,
} from '../ui/menubar';
import { NAV_CONFIG, isMenuActive } from './nav-config';

// Mirrors MenubarTrigger's visual language (px-3 py-1.5 text-sm rounded-btn)
// so direct links and grouped triggers read as one consistent row.
const directLinkClass = ({ isActive }) =>
  cn(
    'flex cursor-pointer select-none items-center gap-1.5 rounded-btn px-3 py-1.5 text-sm font-medium outline-none transition-colors',
    'hover:bg-s2 hover:text-primary2',
    isActive && 'text-primary2'
  );

export default function NavMenubar({ className }) {
  const location = useLocation();
  const links = NAV_CONFIG.filter(node => node.kind === 'link');
  const menus = NAV_CONFIG.filter(node => node.kind === 'menu');

  return (
    <div className={cn('flex items-center gap-1', className)}>
      {links.map(node => {
        const Icon = node.icon;
        return (
          <NavLink key={node.to} to={node.to} className={directLinkClass}>
            <Icon size={16} aria-hidden="true" />
            {node.label}
          </NavLink>
        );
      })}
      <Menubar className="h-auto border-none bg-transparent p-0">
        {menus.map(node => {
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
    </div>
  );
}
