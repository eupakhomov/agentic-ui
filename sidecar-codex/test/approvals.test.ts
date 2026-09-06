import { describe, expect, it } from 'vitest';
import { allowDecision, denyDecision } from '../src/approvals.js';

describe('allowDecision', () => {
  it('is always accept', () => {
    expect(allowDecision()).toBe('accept');
  });
});

describe('denyDecision', () => {
  it('prefers decline when offered', () => {
    expect(denyDecision(['accept', 'decline'])).toBe('decline');
  });

  it('falls back to cancel when decline is not offered', () => {
    expect(denyDecision(['accept', { acceptWithExecpolicyAmendment: true }, 'cancel'])).toBe('cancel');
  });

  it('falls back to decline when neither decline nor cancel is offered', () => {
    expect(denyDecision(['accept'])).toBe('decline');
  });

  it('falls back to decline when availableDecisions is missing entirely', () => {
    expect(denyDecision(undefined)).toBe('decline');
  });

  it('falls back to decline when availableDecisions is not an array', () => {
    expect(denyDecision('accept')).toBe('decline');
  });
});
