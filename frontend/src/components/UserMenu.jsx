// The signed-in identity + logout control, extracted out of Topbar's row 1.
//
// It used to be an inline chip that spelled out the username AND the words
// "Cerrar sesión" side by side — ~140px of a 390px iPhone row that already
// carries the logo and four rubro tabs. Collapsing it to a 32px avatar trigger
// is the point of the change: the identity is still one tap away, it just
// stops competing with the nav for horizontal space.
//
// Deliberately router-free: everything actionable here (logout) is a callback,
// not a destination. `Cuentas` lives in nav-config.js for ADMINs already, and
// duplicating it here would buy a `useNavigate()` that makes this component
// unmountable outside a <Router>.
import { LogOut, ChevronDown } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useAuth } from '../auth/AuthProvider';

/** Same rule UsuariosAdminPanel uses: today an account has exactly one role. */
function rolDe(identity) {
  return identity.roles?.[0] || '—';
}

/**
 * Up to two letters. Splits on the separators a username actually uses
 * (`santi.martinez`, `santi_m`, `santi m`) so a compound name reads as two
 * initials instead of its first two characters.
 */
export function iniciales(username = '') {
  const partes = username.split(/[.\-_\s]+/).filter(Boolean);
  if (partes.length === 0) return '?';
  if (partes.length === 1) return partes[0].slice(0, 2).toUpperCase();
  return (partes[0][0] + partes[1][0]).toUpperCase();
}

function Avatar({ username, className = '' }) {
  return (
    <span
      aria-hidden="true"
      className={`flex shrink-0 items-center justify-center rounded-full
                  bg-primary font-bold leading-none text-white ${className}`}>
      {iniciales(username)}
    </span>
  );
}

export default function UserMenu() {
  const { identity, logout } = useAuth();
  if (!identity) return null;

  const { username } = identity;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={`Sesión de ${username}`}
        className="flex items-center gap-[6px] rounded-full border-[1.5px] border-border
                   p-[3px] pr-[3px] text-t2 outline-none transition-colors
                   hover:border-primary focus-visible:border-primary
                   data-[state=open]:border-primary sm:pr-[9px]">
        <span className="relative">
          <Avatar username={username} className="size-8 text-[.68rem]" />
          {/* Presence dot: the session is live, which is the only "status"
              this app has. Ringed in the topbar's own surface so it reads as
              cut out of the avatar rather than floating over it. */}
          <span className="absolute bottom-0 right-0 size-[8px] rounded-full bg-success ring-2 ring-s1" />
        </span>
        {/* The name is redundant with the avatar on a phone — hidden below sm,
            where the row has no space for it. */}
        <span className="hidden max-w-[9rem] truncate text-[.7rem] font-semibold sm:inline">
          {username}
        </span>
        <ChevronDown className="hidden size-3 text-t4 sm:block" aria-hidden="true" />
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-[min(15rem,calc(100vw-1.5rem))]">
        <DropdownMenuLabel className="flex items-center gap-[10px] px-2 py-2">
          <Avatar username={username} className="size-10 text-[.82rem]" />
          <span className="flex min-w-0 flex-col">
            <span className="truncate text-[.82rem] font-semibold text-t1">{username}</span>
            <span className="text-[.68rem] font-normal uppercase tracking-wide text-t4">
              {rolDe(identity)}
            </span>
          </span>
        </DropdownMenuLabel>

        <DropdownMenuSeparator />

        <DropdownMenuItem destructive onSelect={logout}>
          <LogOut className="size-4" aria-hidden="true" />
          <span>Cerrar sesión</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
