// Single source of truth for the primary navigation. Both the desktop
// Menubar (NavMenubar.jsx) and the mobile drawer (NavDrawer.jsx) render
// from this same array so the two surfaces can never drift apart.
import {
  ShoppingBag, Trophy, Sparkles,
  Compass, LineChart, Bookmark,
  Tag, Pill, TrendingUp, Scale, CreditCard, Star, Shirt,
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
      { label: 'Tendencias', to: '/tendencias', icon: TrendingUp },
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
];

// Prefix match so nested routes (e.g. /picks/zapatillas) still activate
// their parent destination — mirrors react-router NavLink's default
// isActive semantics.
export function isLinkActive(pathname, to) {
  return pathname === to || pathname.startsWith(to + '/');
}

export function isMenuActive(pathname, items) {
  return items.some(item => isLinkActive(pathname, item.to));
}
