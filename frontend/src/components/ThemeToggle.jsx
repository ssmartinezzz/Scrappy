import { Moon, Sun } from 'lucide-react';
import useTheme from '../hooks/useTheme';

export default function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const dark = theme === 'dark';
  const Icon = dark ? Sun : Moon;
  return (
    <button type="button" onClick={toggle} aria-pressed={dark}
      aria-label={dark ? 'Activar modo claro' : 'Activar modo oscuro'}
      title={dark ? 'Modo claro' : 'Modo oscuro'}
      className="flex h-[32px] w-[32px] shrink-0 cursor-pointer items-center justify-center rounded-full
                 border-[1.5px] border-border bg-transparent text-t3 transition-colors hover:border-primary hover:text-primary2">
      <Icon size={15} aria-hidden="true" />
    </button>
  );
}
