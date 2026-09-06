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
