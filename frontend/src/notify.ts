// Desktop notifications for unattended sessions: agent finished, needs input, crashed.

const PREF_KEY = 'claude-ui.notifications';

export function notificationsEnabled(): boolean {
  return localStorage.getItem(PREF_KEY) === 'on' && 'Notification' in window && Notification.permission === 'granted';
}

export async function toggleNotifications(): Promise<boolean> {
  if (!('Notification' in window)) {
    console.warn('[notify] Notification API unavailable (insecure context? use localhost or https)');
    return false;
  }
  if (notificationsEnabled()) {
    localStorage.setItem(PREF_KEY, 'off');
    return false;
  }
  const permission = await Notification.requestPermission();
  if (permission === 'granted') {
    localStorage.setItem(PREF_KEY, 'on');
    // immediate test notification, bypassing the focus check: if this one does not
    // appear, the browser/OS layer (e.g. Windows Focus Assist) is eating them
    show('claude-ui notifications enabled', 'If you can read this, delivery works. Session events notify only while this window is unfocused.');
    return true;
  }
  console.warn('[notify] permission not granted:', permission);
  return false;
}

/** Notify only when the dashboard isn't being watched. */
export function notify(title: string, body: string): void {
  if (!notificationsEnabled()) {
    console.debug('[notify] suppressed (disabled or no permission):', title);
    return;
  }
  if (document.hasFocus()) {
    console.debug('[notify] suppressed (dashboard window is focused):', title);
    return;
  }
  show(title, body);
}

function show(title: string, body: string): void {
  const n = new Notification(title, { body, tag: title, requireInteraction: false });
  n.onclick = () => {
    window.focus();
    n.close();
  };
}
