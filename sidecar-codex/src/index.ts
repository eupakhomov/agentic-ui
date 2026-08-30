import { statSync } from 'node:fs';
import { resolve } from 'node:path';
import { runSession, type CodexPermissionMode, type SidecarConfig } from './session.js';
import type { EffortLevel } from './protocol.js';
import { log } from './stdio.js';

const PERMISSION_MODES: CodexPermissionMode[] = ['default', 'bypassPermissions'];
const EFFORT_LEVELS: EffortLevel[] = ['low', 'medium', 'high', 'xhigh', 'max'];

/**
 * Deliberately narrower than sidecar/'s flag set — Codex has no analog for
 * --thinking, --mcp-config, --allowed-tools/--disallowed-tools,
 * --append-system-prompt, --context-dir, --fallback-model, or --max-turns for this
 * MVP pass (see docs/plan/phase-5.13-codex-provider.md "Out of scope"). Passing any
 * of them is a usage error, not a silently-ignored no-op — SessionService must not
 * pass flags this adapter can't honor.
 */
function usage(): never {
  console.error(
    `usage: sidecar-codex --cwd <dir> [--resume <thread-id>] [--model <m>]
       [--permission-mode ${PERMISSION_MODES.join('|')}]
       [--effort ${EFFORT_LEVELS.join('|')}]
       [--append-system-prompt <text>]`,
  );
  process.exit(2);
}

function parseArgs(argv: string[]): SidecarConfig {
  const config: SidecarConfig = { cwd: '' };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = argv[i + 1];
    const need = (): string => {
      if (value === undefined) usage();
      i++;
      return value;
    };
    switch (flag) {
      case '--cwd':
        config.cwd = resolve(need());
        break;
      case '--resume':
        config.resume = need();
        break;
      case '--model':
        config.model = need();
        break;
      case '--permission-mode': {
        const mode = need() as CodexPermissionMode;
        if (!PERMISSION_MODES.includes(mode)) usage();
        config.permissionMode = mode;
        break;
      }
      case '--effort': {
        const level = need() as EffortLevel;
        if (!EFFORT_LEVELS.includes(level)) usage();
        config.effort = level;
        break;
      }
      case '--append-system-prompt':
        config.appendSystemPrompt = need();
        break;
      default:
        usage();
    }
  }
  if (!config.cwd) usage();
  return config;
}

const config = parseArgs(process.argv.slice(2));
try {
  if (!statSync(config.cwd).isDirectory()) throw new Error('not a directory');
} catch {
  console.error(`sidecar-codex: cwd is not a usable directory: ${config.cwd}`);
  process.exit(2);
}

log(`starting in ${config.cwd} (pid ${process.pid})`);
void runSession(config);
