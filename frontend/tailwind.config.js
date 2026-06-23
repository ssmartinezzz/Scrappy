/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--bg)',
        s1: 'var(--s1)',
        s2: 'var(--s2)',
        s3: 'var(--s3)',
        t1: 'var(--t1)',
        t2: 'var(--t2)',
        t3: 'var(--t3)',
        t4: 'var(--t4)',
        border: 'var(--bd)',
        bd2: 'var(--bd2)',
        primary: 'var(--p)',
        primary2: 'var(--p2)',
        success: 'var(--g)',
        danger: 'var(--r)',
        warning: 'var(--y)',
      },
      borderRadius: {
        card: 'var(--rc)',
        btn: 'var(--rb)',
      },
      spacing: {
        1: '8px',
        2: '16px',
        3: '24px',
        4: '32px',
        5: '40px',
        6: '48px',
      },
      fontFamily: {
        sans: ['Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['Cascadia Code', 'JetBrains Mono', 'Consolas', 'SF Mono', 'Menlo', 'monospace'],
        display: ['Archivo', 'Segoe UI', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        'display-1': ['2.5rem', { fontWeight: '800', lineHeight: '1.1' }],
        'display-2': ['1.875rem', { fontWeight: '800', lineHeight: '1.15' }],
        eyebrow: ['.68rem', { fontWeight: '700', letterSpacing: '.08em', lineHeight: '1.4' }],
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
};
