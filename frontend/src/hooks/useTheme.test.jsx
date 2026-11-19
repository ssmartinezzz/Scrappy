import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import useTheme from './useTheme';

function stubMatchMedia(prefersDark) {
  window.matchMedia = vi.fn().mockReturnValue({ matches: prefersDark });
}

beforeEach(() => {
  document.querySelectorAll('style[data-theme-switch]').forEach(n => n.remove());
  window.localStorage.clear();
  document.documentElement.classList.remove('dark');
  document.documentElement.style.colorScheme = '';
  stubMatchMedia(false);
});

afterEach(() => { delete window.matchMedia; });

describe('useTheme', () => {
  it('starts from the saved choice, over the OS preference', () => {
    window.localStorage.setItem('theme', 'light');
    stubMatchMedia(true);
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('light');
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('falls back to the OS preference when nothing valid is saved', () => {
    window.localStorage.setItem('theme', 'purple');
    stubMatchMedia(true);
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('dark');
    expect(document.documentElement).toHaveClass('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });

  it('works without matchMedia', () => {
    delete window.matchMedia;
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('light');
  });

  it('toggle flips the class, color-scheme and the saved value', () => {
    const { result } = renderHook(() => useTheme());
    act(() => result.current.toggle());
    expect(result.current.theme).toBe('dark');
    expect(document.documentElement).toHaveClass('dark');
    expect(window.localStorage.getItem('theme')).toBe('dark');
    act(() => result.current.toggle());
    expect(document.documentElement).not.toHaveClass('dark');
    expect(document.documentElement.style.colorScheme).toBe('light');
    expect(window.localStorage.getItem('theme')).toBe('light');
  });

  it('disables transitions during a switch and restores them after', async () => {
    const { result } = renderHook(() => useTheme());
    expect(document.querySelector('style[data-theme-switch]')).toBeNull();
    act(() => result.current.toggle());
    expect(document.querySelector('style[data-theme-switch]').textContent).toContain('transition:none');
    await waitFor(() => expect(document.querySelector('style[data-theme-switch]')).toBeNull());
  });

  it('still toggles when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    const { result } = renderHook(() => useTheme());
    act(() => result.current.toggle());
    expect(result.current.theme).toBe('dark');
    expect(document.documentElement).toHaveClass('dark');
  });
});
