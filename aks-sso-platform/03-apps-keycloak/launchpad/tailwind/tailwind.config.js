/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['../src/main/resources/templates/**/*.html', '../src/main/resources/static/js/*.js'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
    },
  },
  safelist: ['badge-up', 'badge-down', 'badge-unknown', 'bg-emerald-500', 'bg-amber-500',
    'border-emerald-200', 'bg-emerald-50', 'text-emerald-700', 'border-amber-200', 'bg-amber-50', 'text-amber-700'],
  plugins: [],
};
