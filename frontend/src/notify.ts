// Desktop notifications for unattended sessions: agent finished, needs input, crashed.

const PREF_KEY = 'claude-ui.notifications';

export function notificationsEnabled(): boolean {
  return localStorage.getItem(PREF_KEY) === 'on' && 'Notification' in window && Notification.permission === 'granted';
}

export async function toggleNotifications(): Promise<boolean> {
  if (!('Notification' in window)) return false;
  if (notificationsEnabled()) {
    localStorage.setItem(PREF_KEY, 'off');
    return false;
  }
  const permission = await Notification.requestPermission();
  if (permission === 'granted') {
    localStorage.setItem(PREF_KEY, 'on');
    return true;
  }
  return false;
}

/**
 * Notify only when the dashboard isn't being watched. The tab-title badge fires
 * regardless of the desktop-notification setting — it survives OS do-not-disturb
 * (e.g. Windows Focus Assist during full-screen video).
 */
export function notify(title: string, body: string): void {
  if (document.hasFocus()) return;
  bumpTitleBadge();
  if (notificationsEnabled()) {
    show(title, body);
  }
}

let pendingCount = 0;
const BASE_TITLE = 'claude-ui';

function bumpTitleBadge(): void {
  pendingCount++;
  document.title = `(${pendingCount}) ${BASE_TITLE}`;
}

window.addEventListener('focus', () => {
  pendingCount = 0;
  document.title = BASE_TITLE;
});

function show(title: string, body: string): void {
  const n = new Notification(title, { body, tag: title, requireInteraction: false });
  n.onclick = () => {
    window.focus();
    n.close();
  };
}
