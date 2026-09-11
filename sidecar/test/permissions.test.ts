import { describe, expect, it } from 'vitest';
import { readOnlyDenial } from '../src/permissions.js';

const CWD = '/work/session1';

describe('readOnlyDenial', () => {
  it('allows a relative path inside the worktree', () => {
    expect(readOnlyDenial('Edit', { file_path: 'src/foo.ts' }, CWD)).toBeUndefined();
  });

  it('allows a path equal to the worktree root itself', () => {
    expect(readOnlyDenial('Edit', { file_path: '.' }, CWD)).toBeUndefined();
  });

  it('denies a relative path that escapes the worktree via ..', () => {
    const denial = readOnlyDenial('Write', { file_path: '../outside.txt' }, CWD);
    expect(denial).toContain('auto-denied');
    expect(denial).toContain(CWD);
  });

  it('denies an absolute path outside the worktree', () => {
    expect(readOnlyDenial('Edit', { file_path: '/etc/passwd' }, CWD)).toContain('auto-denied');
  });

  it('denies a sibling directory that merely shares the worktree path as a string prefix', () => {
    // /work/session1 is a string-prefix of /work/session10/file.ts but not an ancestor —
    // the startsWith check must be anchored on a path separator, not just the raw string.
    expect(readOnlyDenial('Edit', { file_path: '/work/session10/file.ts' }, CWD)).toContain('auto-denied');
  });

  it('resolves notebook_path the same way as file_path', () => {
    expect(readOnlyDenial('NotebookEdit', { notebook_path: '/etc/x.ipynb' }, CWD)).toContain('auto-denied');
    expect(readOnlyDenial('NotebookEdit', { notebook_path: 'nb.ipynb' }, CWD)).toBeUndefined();
  });

  it('never denies tools outside the file-modifying set, e.g. Bash', () => {
    expect(readOnlyDenial('Bash', { command: 'rm -rf /etc' }, CWD)).toBeUndefined();
  });

  it('never denies a file-modifying tool call with no recognizable path field', () => {
    expect(readOnlyDenial('Edit', { old_string: 'a', new_string: 'b' }, CWD)).toBeUndefined();
  });
});

describe('readOnlyDenial with an explicit writableRoot (docs/plan/phase-11-monorepo.md)', () => {
  // a monorepo session: cwd is the service subfolder, writableRoot is the wider worktree root
  const WORKTREE = '/work/session1';
  const SERVICE_CWD = '/work/session1/packages/foo';

  it('allows a relative-path write under a sibling package, not just the service subfolder', () => {
    expect(
      readOnlyDenial('Edit', { file_path: '../bar/x.ts' }, SERVICE_CWD, WORKTREE),
    ).toBeUndefined();
  });

  it('allows an absolute-path write under a sibling package', () => {
    expect(
      readOnlyDenial('Edit', { file_path: '/work/session1/packages/bar/x.ts' }, SERVICE_CWD, WORKTREE),
    ).toBeUndefined();
  });

  it('denies a write to the original checkout the worktree was cut from, naming the wider writableRoot', () => {
    const denial = readOnlyDenial('Write', { file_path: '/original/checkout/packages/foo/x.ts' }, SERVICE_CWD, WORKTREE);
    expect(denial).toContain('auto-denied');
    expect(denial).toContain(WORKTREE);
  });

  it('still resolves a relative path against cwd, not writableRoot, before checking the wider boundary', () => {
    // "src/foo.ts" relative to SERVICE_CWD is .../packages/foo/src/foo.ts — inside WORKTREE either way,
    // but proves resolution didn't silently switch to resolving against writableRoot instead of cwd.
    expect(readOnlyDenial('Edit', { file_path: 'src/foo.ts' }, SERVICE_CWD, WORKTREE)).toBeUndefined();
  });
});
