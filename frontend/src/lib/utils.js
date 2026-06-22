import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs) {
  return twMerge(clsx(inputs));
}

export function sortByCountDesc(obj) {
  return Object.entries(obj).sort((a, b) => b[1] - a[1]);
}
