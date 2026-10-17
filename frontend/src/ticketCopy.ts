/**
 * Wording for the wait while a ticket list/import request is in flight. `busy` is the backend's
 * snapshot (from GET /api/tickets/import/enabled) of whether the interactive system worker was
 * already running another task when the dialog opened — then this request queues behind it.
 */
export function ticketWaitCopy(warm: boolean, busy: boolean): string {
  if (busy) return 'finishing another assistant task, then fetching from Linear…';
  return warm
    ? 'fetching from Linear…'
    : 'fetching from Linear — can take up to 45s on the first call (spinning up the system session)…';
}
