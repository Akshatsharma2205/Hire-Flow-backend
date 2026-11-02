/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#F0FDFA',
          100: '#CCFBF1',
          200: '#99F6E4', // focus ring
          300: '#5EEAD4',
          400: '#2DD4BF', // dark mode accent
          500: '#14B8A6',
          600: '#0D9488',
          700: '#0F766E', // primary light brand accent
          800: '#115E59', // brand hover
          900: '#134E4A',
          950: '#042F2E',
          DEFAULT: '#0F766E',
        },
        surface: {
          light: {
            bg: '#F7F8FA',
            main: '#FFFFFF',
            secondary: '#F9FAFB',
            border: '#EAECF0',
          },
          dark: {
            bg: '#0B1220',
            main: '#111827',
            elevated: '#182230',
            border: '#263244',
          },
          850: '#151d2e',
          900: '#0f172a',
          950: '#080d19',
        },
        'app-bg': {
          light: '#F7F8FA',
          dark: '#0B1220',
        },
        'surface-main': {
          light: '#FFFFFF',
          dark: '#111827',
        },
        'surface-secondary': {
          light: '#F9FAFB',
          dark: '#182230',
        },
      },
      fontFamily: {
        sans: ['Plus Jakarta Sans', 'Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace']
      }
    },
  },
  plugins: [
    require('@tailwindcss/typography'),
    require('@tailwindcss/forms'),
  ],
}
