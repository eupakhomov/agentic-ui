import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { applyPrefs } from './prefs';

applyPrefs();
createRoot(document.getElementById('root')!).render(<App />);
