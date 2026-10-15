import { describe, expect, it } from 'vitest';
import { needsFullCard, permissionResponseFor, permissionSummary, toolLabel } from './permissions';

describe('permissionResponseFor', () => {
  it('plain tools get a plain allow/deny', () => {
    expect(permissionResponseFor('Bash', false, 'allow')).toEqual({ behavior: 'allow' });
    expect(permissionResponseFor('Bash', false, 'deny')).toEqual({ behavior: 'deny' });
  });

  it('AskUserQuestion has no blind allow and a skip message on deny', () => {
    expect(permissionResponseFor('AskUserQuestion', false, 'allow')).toBeNull();
    expect(permissionResponseFor('AskUserQuestion', false, 'deny')).toMatchObject({ behavior: 'deny' });
  });

  it('a plan deny keeps planning', () => {
    expect(permissionResponseFor('ExitPlanMode', true, 'deny')).toEqual({
      behavior: 'deny', message: 'Keep planning; do not execute yet.',
    });
  });
});

describe('needsFullCard', () => {
  it('is true for plans and questions only', () => {
    expect(needsFullCard('ExitPlanMode', true)).toBe(true);
    expect(needsFullCard('AskUserQuestion', false)).toBe(true);
    expect(needsFullCard('Edit', false)).toBe(false);
  });
});

describe('toolLabel', () => {
  it('shortens MCP tools, names plans and questions, leaves the rest', () => {
    expect(toolLabel('mcp__memory__spawn_child_session', false)).toBe('spawn_child_session');
    expect(toolLabel('mcp__agentic-ui__report_result', false)).toBe('report_result');
    expect(toolLabel('ExitPlanMode', true)).toBe('Plan');
    expect(toolLabel('AskUserQuestion', false)).toBe('Question');
    expect(toolLabel('Bash', false)).toBe('Bash');
  });
});

describe('permissionSummary', () => {
  it('shows a Bash command with whitespace collapsed', () => {
    expect(permissionSummary('Bash', { command: 'git  status\n  --short' })).toBe('git status --short');
  });

  it('shows the file path for file tools', () => {
    expect(permissionSummary('Edit', { file_path: '/w/src/a.ts', old_string: 'x', new_string: 'y' })).toBe('/w/src/a.ts');
  });

  it('shows the first question for AskUserQuestion', () => {
    expect(permissionSummary('AskUserQuestion', { questions: [{ question: 'Which file?', options: [] }] })).toBe('Which file?');
  });

  it('falls back to truncated JSON for anything else', () => {
    const out = permissionSummary('mcp__x__y', { q: 'a'.repeat(200) });
    expect(out.startsWith('{"q":"aaa')).toBe(true);
    expect(out.endsWith('…')).toBe(true);
    expect(out.length).toBe(81);
  });
});
