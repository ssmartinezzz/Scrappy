import { useCallback, useEffect, useRef, useState } from 'react';

const STORAGE_KEY = 'theme';

function readStored() {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return v === 'light' || v === 'dark' ? v : null;
  } catch {
    return null;
  }
}

function systemPrefersDark() {
  try {
    return typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

// Like next-themes' disableTransitionOnChange: `transition: all` rules would
// otherwise start a colour transition on every element at once (~400 ms on a
// 300-card grid). The reflow read commits the new colours while transitions
// are off; the style is removed on the next frame.
function withoutTransitions() {
  const style = document.createElement('style');
  style.setAttribute('data-theme-switch', '');
  style.textContent = '*,*::before,*::after{transition:none!important}';
  document.head.appendChild(style);
  void window.getComputedStyle(document.body).opacity;
  const remove = () => style.remove();
  if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(() => window.requestAnimationFrame(remove));
  else setTimeout(remove, 0);
}

function applyTheme(theme, animate = true) {
  if (animate) withoutTransitions();
  const root = document.documentElement;
  root.classList.toggle('dark', theme === 'dark');
  root.style.colorScheme = theme;
  // Both metas (light/dark media variants) get the chosen colour, so a manual
  // choice that differs from the OS setting still tints the browser chrome.
  const color = theme === 'dark' ? '#16130F' : '#FBF7EF';
  document.querySelectorAll('meta[name="theme-color"]').forEach(m => m.setAttribute('content', color));
}

/**
 * Light/dark theme. Initial value: the saved choice, else the OS preference.
 * The `dark` class on <html> is what styles.css keys off; index.html applies
 * it before first paint with the same rule, so this hook only has to keep it
 * in sync afterwards.
 */
export default function useTheme() {
  const mounted = useRef(false);
  const [theme, setTheme] = useState(() => readStored() ?? (systemPrefersDark() ? 'dark' : 'light'));

  useEffect(() => {
    applyTheme(theme, mounted.current);
    mounted.current = true;
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme(prev => {
      const next = prev === 'dark' ? 'light' : 'dark';
      try { window.localStorage.setItem(STORAGE_KEY, next); } catch { /* private mode: the choice lasts the session */ }
      return next;
    });
  }, []);

  return { theme, toggle };
}
