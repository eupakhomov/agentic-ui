import { describe, expect, it } from 'vitest';
import { sandboxPolicyFor } from '../src/session.js';

describe('sandboxPolicyFor (docs/plan/phase-11-monorepo.md Step 4)', () => {
  it('carries the configured writable root through to workspaceWrite.writableRoots', () => {
    const policy = sandboxPolicyFor('default', '/work/session1');

    expect(policy).toEqual({
      type: 'workspaceWrite',
      writableRoots: ['/work/session1'],
      networkAccess: false,
      excludeTmpdirEnvVar: false,
      excludeSlashTmp: false,
    });
  });

  it('reflects a different writable root than the previous call, proving the value is threaded through, not hardcoded', () => {
    // a monorepo session's writableRoot is the worktree root, distinct from its cwd (the service
    // subfolder) — sandboxPolicyFor itself is agnostic to that, it just carries whatever it's given
    const policy = sandboxPolicyFor('default', '/work/session2') as { writableRoots: string[] };

    expect(policy.writableRoots).toEqual(['/work/session2']);
  });

  it('ignores the writable root for bypassPermissions, same as before this phase', () => {
    const policy = sandboxPolicyFor('bypassPermissions', '/work/session1');

    expect(policy).toEqual({ type: 'dangerFullAccess' });
  });
});
