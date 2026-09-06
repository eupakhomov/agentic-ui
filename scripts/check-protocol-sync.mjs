#!/usr/bin/env node
// Guards sidecar-codex/'s documented "synced copy" of sidecar/'s shared, provider-neutral
// protocol types (see sidecar-codex/src/protocol.ts's header comment and CLAUDE.md's
// "Codex provider adapter" section) — without this, protocol.ts/stdio.ts can silently
// drift out of sync since nothing else compiles them against each other.
// See docs/plan/phase-9-production-hardening.md T5.
//
// Each pair below is compared after normalizing away exactly the differences the two
// files' own comments document as intentional (a capability-const block that's
// legitimately package-specific, a per-package log prefix, and prose differences in
// comments) — anything else that differs is real drift and fails the check.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const pairs = [
  {
    a: 'sidecar/src/protocol.ts',
    b: 'sidecar-codex/src/protocol.ts',
    normalize: normalizeProtocol,
  },
  {
    a: 'sidecar/src/stdio.ts',
    b: 'sidecar-codex/src/stdio.ts',
    normalize: normalizeStdio,
  },
];

function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^[ \t]*\/\/.*$/gm, '');
}

function collapseBlankLines(text) {
  return text
    .split('\n')
    .map((line) => line.trimEnd())
    .filter((line) => line.trim() !== '')
    .join('\n');
}

// The *_CAPABILITIES const is explicitly documented as "specific to this package" —
// each provider's capability set is supposed to differ. Strip the whole block so only
// the shared type declarations above it are compared.
function stripCapabilitiesBlock(text) {
  return text.replace(/export const \w+_CAPABILITIES: Capabilities = \{[\s\S]*?\n\};\n?/g, '');
}

function normalizeProtocol(text) {
  return collapseBlankLines(stripCapabilitiesBlock(stripComments(text)));
}

// Each sidecar's log() prefix names itself on purpose (`[sidecar]` vs `[sidecar-codex]`) —
// normalize just that string literal so the rest of the line (and file) is still compared.
function normalizeStdio(text) {
  const withoutComments = stripComments(text);
  const normalizedPrefix = withoutComments.replace(/console\.error\('\[[^\]]*\]'/g, "console.error('[SIDECAR]'");
  return collapseBlankLines(normalizedPrefix);
}

function firstDiffLine(left, right) {
  const leftLines = left.split('\n');
  const rightLines = right.split('\n');
  const max = Math.max(leftLines.length, rightLines.length);
  for (let i = 0; i < max; i++) {
    if (leftLines[i] !== rightLines[i]) {
      return { line: i + 1, left: leftLines[i] ?? '(missing)', right: rightLines[i] ?? '(missing)' };
    }
  }
  return null;
}

let failed = false;

for (const { a, b, normalize } of pairs) {
  const aPath = path.join(repoRoot, a);
  const bPath = path.join(repoRoot, b);
  const aNorm = normalize(readFileSync(aPath, 'utf8'));
  const bNorm = normalize(readFileSync(bPath, 'utf8'));

  if (aNorm !== bNorm) {
    failed = true;
    const diff = firstDiffLine(aNorm, bNorm);
    console.error(`\nprotocol drift: ${a} and ${b} no longer match outside their documented exceptions.`);
    if (diff) {
      console.error(`  first mismatch at normalized line ${diff.line}:`);
      console.error(`    ${a}: ${diff.left}`);
      console.error(`    ${b}: ${diff.right}`);
    }
    console.error('  If this is a deliberate protocol v1 change, update both copies to match.');
    console.error('  If it is a new intentional per-package exception, extend this script\'s normalization.');
  } else {
    console.log(`ok: ${a} <-> ${b}`);
  }
}

if (failed) {
  process.exit(1);
}
