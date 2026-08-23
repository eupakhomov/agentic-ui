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

/** Notify only when the dashboard isn't being watched. */
export function notify(title: string, body: string): void {
  if (!notificationsEnabled() || document.hasFocus()) return;
  const n = new Notification(title, { body, tag: title + body });
  n.onclick = () => {
    window.focus();
    n.close();
  };
}
