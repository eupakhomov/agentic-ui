import type { EditorState } from '@codemirror/state';
import type { ChangedFile } from '../protocol';

/** A line-aligned selection inside the changes panel's diff. `base` = the merge-base side of a split diff. */
export interface LineSelection {
  side: 'base' | 'new';
  fromLine: number;
  toLine: number;
  text: string;
}

const MAX_ASK_CHARS = 20_000;

/**
 * The editor's main selection widened to whole lines (a quoted fragment without its line is hard to
 * read), or null when nothing is selected. A selection that ends at column 0 of a line (what a
 * triple-click or shift+down produces) does not include that following line.
 */
export function selectionFromState(state: EditorState, side: LineSelection['side']): LineSelection | null {
  const { from, to, empty } = state.selection.main;
  if (empty) return null;
  const first = state.doc.lineAt(from);
  let last = state.doc.lineAt(to);
  if (to > from && last.from === to && last.number > first.number) {
    last = state.doc.line(last.number - 1);
  }
  let text = state.doc.sliceString(first.from, last.to);
  if (text.length > MAX_ASK_CHARS) {
    text = text.slice(0, MAX_ASK_CHARS) + '\n… (selection truncated)';
  }
  return { side, fromLine: first.number, toLine: last.number, text };
}

const FENCE_LANG: Record<string, string> = {
  java: 'java', kt: 'kotlin', kts: 'kotlin', ts: 'typescript', tsx: 'tsx', js: 'javascript', jsx: 'jsx',
  mjs: 'javascript', cjs: 'javascript', json: 'json', md: 'markdown', yaml: 'yaml', yml: 'yaml', xml: 'xml',
  html: 'html', htm: 'html', css: 'css', sql: 'sql', py: 'python', sh: 'bash', bash: 'bash', gradle: 'groovy',
  groovy: 'groovy', properties: 'properties', toml: 'toml',
};

export function extensionOf(path: string): string {
  const name = path.slice(path.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  return dot <= 0 ? '' : name.slice(dot + 1).toLowerCase();
}

/**
 * The text dropped into the composer by "Ask about these lines": where the lines are, then the
 * lines in a fenced block (fence longer than any backtick run inside), then a blank line for the
 * question. The user types the question after it.
 */
export function buildAskPrompt(path: string, sel: LineSelection): string {
  const range = sel.fromLine === sel.toLine ? `line ${sel.fromLine}` : `lines ${sel.fromLine}–${sel.toLine}`;
  const side = sel.side === 'base' ? ' (before the change)' : '';
  const longestRun = Math.max(0, ...(sel.text.match(/`+/g) ?? []).map((r) => r.length));
  const fence = '`'.repeat(Math.max(3, longestRun + 1));
  return `In \`${path}\` ${range}${side}:\n${fence}${FENCE_LANG[extensionOf(path)] ?? ''}\n${sel.text}\n${fence}\n\n`;
}

/** What the "changed on disk" banner compares: anything that moves when the file or its git state does. */
export function changeSignature(f: ChangedFile): string {
  return [f.status, f.where.join(','), f.additions, f.deletions, f.mtime].join('|');
}

/** Appends the ask text to whatever the user already typed, separated by a blank line. */
export function appendToComposer(current: string, addition: string): string {
  if (!current.trim()) return addition;
  return current.replace(/\s+$/, '') + '\n\n' + addition;
}
