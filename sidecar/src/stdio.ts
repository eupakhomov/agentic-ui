import type { Readable } from 'node:stream';
import type { Event } from './protocol.js';

/** stdout is protocol-only; every diagnostic goes here. */
export function log(...args: unknown[]): void {
  console.error('[sidecar]', ...args);
}

export function writeEvent(event: Event): void {
  process.stdout.write(JSON.stringify(event) + '\n');
}

/**
 * Line-framed reader with no line-length limit. Invokes onLine per complete line,
 * onEnd when the stream closes (an incomplete trailing line is delivered first).
 */
export function readLines(
  stream: Readable,
  onLine: (line: string) => void,
  onEnd: () => void,
): void {
  let buffer = '';
  stream.setEncoding('utf8');
  stream.on('data', (chunk: string) => {
    buffer += chunk;
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '');
      buffer = buffer.slice(idx + 1);
      if (line.trim() !== '') onLine(line);
    }
  });
  stream.on('end', () => {
    const rest = buffer.trim();
    if (rest !== '') onLine(rest);
    onEnd();
  });
}
