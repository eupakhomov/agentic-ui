import { describe, expect, it } from 'vitest';
import { EditorSelection, EditorState } from '@codemirror/state';
import { appendToComposer, buildAskPrompt, changeSignature, extensionOf, selectionFromState } from './selection';
import type { ChangedFile } from '../protocol';

function stateWith(doc: string, anchor: number, head: number): EditorState {
  return EditorState.create({ doc, selection: EditorSelection.single(anchor, head) });
}

describe('selectionFromState', () => {
  const doc = 'one\ntwo\nthree\nfour\n';

  it('is null for a bare caret', () => {
    expect(selectionFromState(stateWith(doc, 5, 5), 'new')).toBeNull();
  });

  it('widens a mid-line selection to whole lines', () => {
    // "wo\nthr" spans lines 2-3
    const sel = selectionFromState(stateWith(doc, 5, 10), 'new')!;
    expect(sel).toEqual({ side: 'new', fromLine: 2, toLine: 3, text: 'two\nthree' });
  });

  it('does not include the next line when the selection ends at its column 0', () => {
    // from start of "two" to start of "three" (what shift+down / triple-click gives)
    const sel = selectionFromState(stateWith(doc, 4, 8), 'new')!;
    expect(sel.fromLine).toBe(2);
    expect(sel.toLine).toBe(2);
    expect(sel.text).toBe('two');
  });

  it('works with a backwards (head before anchor) selection and keeps the side', () => {
    const sel = selectionFromState(stateWith(doc, 10, 5), 'base')!;
    expect(sel.side).toBe('base');
    expect(sel.fromLine).toBe(2);
    expect(sel.toLine).toBe(3);
  });

  it('truncates a huge selection', () => {
    const big = 'x'.repeat(30_000);
    const sel = selectionFromState(stateWith(big, 0, big.length), 'new')!;
    expect(sel.text.length).toBeLessThan(21_000);
    expect(sel.text).toContain('selection truncated');
  });
});

describe('buildAskPrompt', () => {
  it('names the file and line range, fences the code, and leaves room for the question', () => {
    const text = buildAskPrompt('src/Foo.java', { side: 'new', fromLine: 42, toLine: 57, text: 'int x;' });
    expect(text).toBe('In `src/Foo.java` lines 42–57:\n```java\nint x;\n```\n\n');
  });

  it('says "line" for a single line and marks the old side', () => {
    const text = buildAskPrompt('a/b.kt', { side: 'base', fromLine: 3, toLine: 3, text: 'val a = 1' });
    expect(text.startsWith('In `a/b.kt` line 3 (before the change):')).toBe(true);
    expect(text).toContain('```kotlin');
  });

  it('uses a longer fence when the code contains backticks', () => {
    const text = buildAskPrompt('README.md', { side: 'new', fromLine: 1, toLine: 1, text: '```js\nx\n```' });
    expect(text).toContain('````markdown');
    expect(text.trimEnd().endsWith('````')).toBe(true);
  });

  it('omits the language for an unknown extension', () => {
    expect(buildAskPrompt('Makefile', { side: 'new', fromLine: 1, toLine: 1, text: 'all:' })).toContain('\n```\nall:');
  });
});

describe('extensionOf', () => {
  it('handles nested paths, dotfiles and no extension', () => {
    expect(extensionOf('a/b/Foo.TSX')).toBe('tsx');
    expect(extensionOf('.gitignore')).toBe('');
    expect(extensionOf('dir.d/Makefile')).toBe('');
  });
});

describe('changeSignature', () => {
  const f: ChangedFile = {
    path: 'a', oldPath: null, status: 'modified', where: ['committed', 'unstaged'], additions: 1, deletions: 2, mtime: 5,
  };
  it('moves with line counts, git state and mtime', () => {
    const base = changeSignature(f);
    expect(changeSignature({ ...f })).toBe(base);
    expect(changeSignature({ ...f, additions: 2 })).not.toBe(base);
    expect(changeSignature({ ...f, where: ['committed'] })).not.toBe(base);
    expect(changeSignature({ ...f, mtime: 6 })).not.toBe(base);
  });
});

describe('appendToComposer', () => {
  it('replaces an empty composer and appends to a typed one after a blank line', () => {
    expect(appendToComposer('', 'ask')).toBe('ask');
    expect(appendToComposer('  \n', 'ask')).toBe('ask');
    expect(appendToComposer('why?  \n', 'ask')).toBe('why?\n\nask');
  });
});
