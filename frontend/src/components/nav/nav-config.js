// Single source of truth for the primary navigation. Both the desktop
// Menubar (NavMenubar.jsx) and the mobile drawer (NavDrawer.jsx) render
// from this same array so the two surfaces can never drift apart.
import {
  ShoppingBag, Trophy, Sparkles,
  Compass, LineChart, Bookmark,
  Tag, Pill, TrendingUp, Sparkle, Scale, CreditCard, Star, Shirt, Clock, Users,
} from 'lucide-react';

// kind: 'link'  → direct NavLink, no submenu
// kind: 'menu'  → grouped dropdown (desktop) / labeled section (mobile drawer)
export const NAV_CONFIG = [
  { kind: 'link', label: 'Catálogo', to: '/catalogo', icon: ShoppingBag },
  { kind: 'link', label: 'Picks', to: '/picks', icon: Trophy },
  { kind: 'link', label: 'Para ti', to: '/recomendados', icon: Sparkles },
  {
    kind: 'menu', label: 'Explorar', icon: Compass,
    items: [
      { label: 'Marcas', to: '/marcas', icon: Tag },
      { label: 'Suplementos', to: '/suplementos', icon: Pill },
    ],
  },
  {
    kind: 'menu', label: 'Análisis', icon: LineChart,
    items: [
      { label: 'Mercado', to: '/analisis/mercado', icon: TrendingUp },
      { label: 'Oportunidades', to: '/analisis/oportunidades', icon: Sparkle },
      { label: 'Comparar', to: '/grupos', icon: Scale },
      { label: 'Cuotas', to: '/financiacion', icon: CreditCard },
    ],
  },
  {
    kind: 'menu', label: 'Guardados', icon: Bookmark,
    items: [
      { label: 'Favoritos', to: '/favoritos', icon: Star },
      { label: 'Outfits', to: '/outfits', icon: Shirt },
    ],
  },
  // Los destinos de administración van como links directos al final de la
  // fila/drawer, no agrupados bajo un menú "Admin". Con dos ya daría para
  // agruparlos, y sigue sin hacerse a propósito: el label de primer nivel
  // "Cronjobs" está fijado por nav-config.test.js, NavMenubar.test.jsx,
  // NavDrawer.test.jsx y App.test.jsx, y agrupar es una decisión de
  // navegación aparte — no el precio de sumar una pantalla.
  // frontend-auth-ui Phase 7 (design D6): `requires` marca un nodo como
  // visible SOLO para ese rol — hidden, not disabled (spec
  // frontend-role-awareness). `/api/cron/**` y `/api/usuarios/**` son ADMIN
  // enteros en ApiRoutePolicy.TABLE.
  { kind: 'link', label: 'Cronjobs', to: '/cronjobs', icon: Clock, requires: 'ADMIN' },
  { kind: 'link', label: 'Cuentas', to: '/admin/manage/users', icon: Users, requires: 'ADMIN' },
];

// frontend-auth-ui Phase 7 (design D6, tasks-part2 7.1/7.2). Single source of
// truth for BOTH nav surfaces: filters `NAV_CONFIG` down to what `roles` may
// see. A `menu` node whose `items` all filter out is dropped entirely — an
// empty dropdown trigger is worse than no trigger at all.
export function visibleNav(config, roles = []) {
  const has = role => roles.includes(role);
  return config.reduce((acc, node) => {
    if (node.kind === 'link') {
      if (!node.requires || has(node.requires)) acc.push(node);
      return acc;
    }
    const items = node.items.filter(item => !item.requires || has(item.requires));
    if (items.length > 0) acc.push({ ...node, items });
    return acc;
  }, []);
}

// Prefix match so nested routes (e.g. /picks/zapatillas) still activate
// their parent destination — mirrors react-router NavLink's default
// isActive semantics.
export function isLinkActive(pathname, to) {
  return pathname === to || pathname.startsWith(to + '/');
}

export function isMenuActive(pathname, items) {
  return items.some(item => isLinkActive(pathname, item.to));
}
