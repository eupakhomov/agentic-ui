/**
 * Manual REPL driver for the sidecar: spawns dist/index.js with the given args,
 * pretty-prints protocol events, and turns typed input into commands.
 *
 *   npm run drive -- --cwd /tmp/scratch --model sonnet
 *
 * Input:
 *   plain text            -> user_message
 *   :allow <id> [json]    -> permission_response allow (json = updatedInput)
 *   :deny <id> [reason]   -> permission_response deny
 *   :mode <mode>          -> set_permission_mode
 *   :model <model>        -> set_model
 *   :int                  -> interrupt
 *   :quit                 -> shutdown
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const sidecarMain = join(here, '..', 'dist', 'index.js');

const child = spawn(process.execPath, [sidecarMain, ...process.argv.slice(2)], {
  stdio: ['pipe', 'pipe', 'inherit'],
});

const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
const bold = (s: string) => `\x1b[1m${s}\x1b[0m`;
const yellow = (s: string) => `\x1b[33m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;

let streaming = false;
const endStream = () => {
  if (streaming) {
    process.stdout.write('\n');
    streaming = false;
  }
};

let buffer = '';
child.stdout.setEncoding('utf8');
child.stdout.on('data', (chunk: string) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx);
    buffer = buffer.slice(idx + 1);
    if (line.trim()) handleEvent(line);
  }
});

function handleEvent(line: string): void {
  let e: Record<string, unknown>;
  try {
    e = JSON.parse(line) as Record<string, unknown>;
  } catch {
    endStream();
    console.log(red(`[non-JSON on stdout] ${line}`));
    return;
  }
  switch (e['type']) {
    case 'ready':
      console.log(dim(`ready pid=${e['pid']} provider=${e['provider']} caps=${JSON.stringify(e['capabilities'])}`));
      break;
    case 'system_init':
      console.log(dim(`session=${e['providerSessionId']} model=${e['model']} mode=${e['permissionMode']}`));
      break;
    case 'stream_delta':
      if (e['deltaType'] === 'thinking') process.stdout.write(dim(String(e['text'])));
      else process.stdout.write(String(e['text']));
      streaming = true;
      break;
    case 'assistant_message':
      endStream();
      break;
    case 'tool_started':
      endStream();
      console.log(dim(`[tool ${e['name']} ${JSON.stringify(e['input']).slice(0, 160)}] (${e['toolUseId']})`));
      break;
    case 'tool_result': {
      const out = String(e['output']);
      console.log(dim(`[result${e['isError'] ? ' ERROR' : ''}${e['truncated'] ? ' (truncated)' : ''}: ${out.slice(0, 200).replaceAll('\n', '⏎')}]`));
      break;
    }
    case 'permission_request':
      endStream();
      console.log(yellow(`PERMISSION ${bold(String(e['requestId']))}: ${e['toolName']} ${JSON.stringify(e['input'])}`));
      console.log(yellow(`  respond with  :allow ${e['requestId']}   or   :deny ${e['requestId']} [reason]`));
      break;
    case 'permission_mode_changed':
      console.log(yellow(`mode -> ${e['mode']}`));
      break;
    case 'model_changed':
      console.log(yellow(`model -> ${e['model']}`));
      break;
    case 'turn_complete':
      endStream();
      console.log(dim(`turn_complete ${e['stopReason']} model=${e['model']} cost=$${Number(e['costUsd']).toFixed(4)} turns=${e['numTurns']} ${e['durationMs']}ms`));
      break;
    case 'error':
      endStream();
      console.log(red(`error${e['fatal'] ? ' (fatal)' : ''}: ${e['message']}`));
      break;
    case 'exiting':
      endStream();
      console.log(dim(`exiting: ${e['reason']}`));
      break;
    default:
      endStream();
      console.log(dim(JSON.stringify(e)));
  }
}

const send = (cmd: Record<string, unknown>) => child.stdin.write(JSON.stringify(cmd) + '\n');

const rl = createInterface({ input: process.stdin, prompt: '> ' });
rl.prompt();
rl.on('line', (raw) => {
  const line = raw.trim();
  if (line === '') { rl.prompt(); return; }
  if (line.startsWith(':')) {
    const [word, ...rest] = line.slice(1).split(/\s+/);
    switch (word) {
      case 'allow': {
        const [id, ...jsonParts] = rest;
        const json = jsonParts.join(' ');
        send({ type: 'permission_response', requestId: id, behavior: 'allow', ...(json ? { updatedInput: JSON.parse(json) } : {}) });
        break;
      }
      case 'deny': {
        const [id, ...msg] = rest;
        send({ type: 'permission_response', requestId: id, behavior: 'deny', ...(msg.length ? { message: msg.join(' ') } : {}) });
        break;
      }
      case 'mode': send({ type: 'set_permission_mode', mode: rest[0] }); break;
      case 'model': send({ type: 'set_model', model: rest[0] }); break;
      case 'int': send({ type: 'interrupt' }); break;
      case 'quit': send({ type: 'shutdown' }); break;
      default: console.log(red(`unknown command :${word}`));
    }
  } else {
    send({ type: 'user_message', text: line });
  }
  rl.prompt();
});
rl.on('close', () => child.stdin.end());

child.on('exit', (code) => {
  console.log(dim(`sidecar exited with code ${code}`));
  process.exit(code ?? 0);
});
