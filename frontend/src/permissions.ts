import type { PermissionResponse } from './components/PermissionCard';

/**
 * The one blind allow/deny mapping shared by the y/d hotkeys and the sidebar's inline
 * Allow/Deny (phase 16): AskUserQuestion has no safe blind "allow" (it needs synthesized
 * answers), so allow is a no-op and deny reuses its existing skip message; ExitPlanMode's
 * deny keeps its friendlier "keep planning" wording. Everything else gets the plain
 * allow/deny PermissionCard already sends. Returns null for a deliberate no-op.
 */
export function permissionResponseFor(toolName: string, isPlan: boolean, decision: 'allow' | 'deny'): PermissionResponse | null {
  if (toolName === 'AskUserQuestion') {
    if (decision === 'allow') return null;
    return { behavior: 'deny', message: 'User skipped answering; proceed with your best judgment.' };
  }
  if (isPlan) {
    return decision === 'allow' ? { behavior: 'allow' } : { behavior: 'deny', message: 'Keep planning; do not execute yet.' };
  }
  return decision === 'allow' ? { behavior: 'allow' } : { behavior: 'deny' };
}

/** Plans and questions need reading on the full card — the sidebar offers only "Open" for them. */
export function needsFullCard(toolName: string, isPlan: boolean): boolean {
  return isPlan || toolName === 'AskUserQuestion';
}

/** Short tool label for the sidebar row: `mcp__memory__spawn_child_session` → `spawn_child_session`. */
export function toolLabel(toolName: string, isPlan: boolean): string {
  if (isPlan) return 'Plan';
  if (toolName === 'AskUserQuestion') return 'Question';
  const mcp = /^mcp__[^_]+(?:_[^_]+)*__(.+)$/.exec(toolName);
  return mcp ? mcp[1]! : toolName;
}

const PATH_KEYS = ['file_path', 'notebook_path', 'path'];

/** One-line summary of a permission request's input for the sidebar row. */
export function permissionSummary(toolName: string, input: Record<string, unknown>): string {
  let text: string;
  const questions = input['questions'];
  if (toolName === 'Bash' && typeof input['command'] === 'string') {
    text = input['command'];
  } else if (toolName === 'AskUserQuestion' && Array.isArray(questions) && typeof questions[0]?.question === 'string') {
    text = questions[0].question;
  } else {
    const key = PATH_KEYS.find((k) => typeof input[k] === 'string');
    text = key ? (input[key] as string) : JSON.stringify(input);
  }
  text = text.replace(/\s+/g, ' ').trim();
  return text.length > 80 ? text.slice(0, 80) + '…' : text;
}
