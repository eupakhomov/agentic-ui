// Appearance preferences: theme + font size. Frontend-only, localStorage-backed —
// applied as data-* attributes on <html> so CSS (styles.css) does the actual work.

const THEME_KEY = 'claude-ui.theme';
const FONT_SIZE_KEY = 'claude-ui.fontSize';

export type Theme = 'light' | 'dark' | 'system';
export type FontSize = 'small' | 'medium' | 'large';

export function getTheme(): Theme {
  const v = localStorage.getItem(THEME_KEY);
  return v === 'light' || v === 'dark' ? v : 'system';
}

export function setTheme(theme: Theme): void {
  localStorage.setItem(THEME_KEY, theme);
  applyPrefs();
}

export function getFontSize(): FontSize {
  const v = localStorage.getItem(FONT_SIZE_KEY);
  return v === 'small' || v === 'large' ? v : 'medium';
}

export function setFontSize(size: FontSize): void {
  localStorage.setItem(FONT_SIZE_KEY, size);
  applyPrefs();
}

/** Stamps data-theme / data-font-size onto <html>; call once at startup and after any change. */
export function applyPrefs(): void {
  const root = document.documentElement;
  const theme = getTheme();
  if (theme === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
  root.setAttribute('data-font-size', getFontSize());
}
