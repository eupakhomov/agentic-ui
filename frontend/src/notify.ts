// Desktop notifications for unattended sessions: agent finished, needs input, crashed.

import type { Envelope } from './protocol';

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
 *
 * `evenIfTabFocused` overrides the tab-focus check — for a per-session event on a
 * multi-session dashboard, having the browser tab focused doesn't mean this particular
 * session is the one being watched (it could be a different session, a minimized one,
 * or one scrolled off-screen); the caller decides that and asks to notify anyway.
 */
export function notify(title: string, body: string, opts?: { evenIfTabFocused?: boolean }): void {
  if (document.hasFocus() && !opts?.evenIfTabFocused) return;
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

/**
 * Journal-event → desktop-notification mapping for a single session widget (docs/plan/
 * phase-9-production-hardening.md O4) — one place instead of an inline if/else chain in
 * SessionWidget, in case a future event type needs the same treatment.
 */
export function notificationForEvent(who: string, e: Envelope): { title: string; body: string } | null {
  if (e.type === 'permission_request') {
    return { title: `${who} needs your input`, body: `${e.payload['toolName']} permission requested` };
  }
  if (e.type === 'turn_complete') {
    return { title: `${who} finished`, body: 'the agent completed its turn' };
  }
  if (e.type === 'state_changed' && e.payload['state'] === 'CRASHED') {
    return { title: `${who} crashed`, body: 'the session needs a resume' };
  }
  if (e.type === 'pr_status_changed') {
    const status = e.payload['status'];
    if (status === 'SUCCESS') return { title: `${who}'s PR passed CI`, body: 'checks succeeded' };
    if (status === 'FAILURE') return { title: `${who}'s PR failed CI`, body: 'checks failed — take a look' };
  }
  return null;
}
