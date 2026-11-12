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
import { NAV_CONFIG, isMenuActive, visibleNav } from './nav-config';
import { useAuth } from '../../auth/AuthProvider';

// Mirrors MenubarTrigger's visual language (px-3 py-1.5 text-sm rounded-btn)
// so direct links and grouped triggers read as one consistent row.
const directLinkClass = ({ isActive }) =>
  cn(
    'flex min-h-[40px] shrink-0 cursor-pointer select-none items-center gap-1.5 whitespace-nowrap rounded-full px-3 text-sm font-medium outline-none transition-colors md:min-h-[34px]',
    'hover:bg-s2 hover:text-primary2 focus-visible:bg-s2 focus-visible:text-primary2',
    isActive ? 'bg-[color-mix(in_srgb,var(--p)_12%,transparent)] font-semibold text-primary2' : 'text-t2'
  );

export default function NavMenubar({ className }) {
  const location = useLocation();
  // frontend-auth-ui Phase 7 (design D6): hidden, not disabled — a VIEWER
  // never sees an ADMIN-only nav node in the DOM at all.
  const { identity } = useAuth();
  const config = visibleNav(NAV_CONFIG, identity?.roles || []);
  const links = config.filter(node => node.kind === 'link');
  const menus = config.filter(node => node.kind === 'menu');

  return (
    <div className={cn('flex w-max items-center gap-1', className)}>
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
              <MenubarTrigger data-active={active ? 'true' : undefined}
                className="min-h-[40px] shrink-0 gap-1.5 whitespace-nowrap rounded-full md:min-h-[34px] data-[active=true]:bg-[color-mix(in_srgb,var(--p)_12%,transparent)]">
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
