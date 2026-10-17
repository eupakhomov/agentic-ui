import { describe, expect, it } from 'vitest';
import { ticketWaitCopy } from './ticketCopy';

describe('ticketWaitCopy', () => {
  it('says another task is finishing when the worker was busy', () => {
    expect(ticketWaitCopy(true, true)).toContain('finishing another assistant task');
    expect(ticketWaitCopy(false, true)).toContain('finishing another assistant task');
  });
  it('keeps the warm and cold-start copy otherwise', () => {
    expect(ticketWaitCopy(true, false)).toBe('fetching from Linear…');
    expect(ticketWaitCopy(false, false)).toContain('up to 45s');
  });
});
