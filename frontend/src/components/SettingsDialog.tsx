import { useState } from 'react';
import { getFontSize, getTheme, setFontSize, setTheme, type FontSize, type Theme } from '../prefs';

export default function SettingsDialog({ onClose }: { onClose: () => void }) {
  const [theme, setThemeState] = useState<Theme>(getTheme());
  const [fontSize, setFontSizeState] = useState<FontSize>(getFontSize());

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Settings</h2>
        <h3 style={{ margin: '0 0 10px' }}>Appearance</h3>
        <div className="form-grid">
          <label>Theme</label>
          <select
            value={theme}
            onChange={(e) => {
              const next = e.target.value as Theme;
              setThemeState(next);
              setTheme(next);
            }}
          >
            <option value="system">System</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
          <label>Font size</label>
          <select
            value={fontSize}
            onChange={(e) => {
              const next = e.target.value as FontSize;
              setFontSizeState(next);
              setFontSize(next);
            }}
          >
            <option value="small">Small</option>
            <option value="medium">Medium</option>
            <option value="large">Large</option>
          </select>
        </div>
        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
