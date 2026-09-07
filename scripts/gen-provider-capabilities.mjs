#!/usr/bin/env node
// Generates <package-dir>/capabilities.json from a built provider adapter's own
// *_CAPABILITIES const (sidecar/src/protocol.ts's CLAUDE_CAPABILITIES,
// sidecar-codex/src/protocol.ts's CODEX_CAPABILITIES) — the TS const stays the single
// source of truth; this file is a committed, build-regenerated mirror the backend reads
// at startup (ProviderCatalog) without needing Node on its own classpath.
// See docs/plan/phase-10-review-followups.md R1.
//
// Usage: node gen-provider-capabilities.mjs <package-dir> <exportName>
// Run as a postbuild step (after tsc) from each package's own "build" script.

import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const [, , pkgDir, exportName] = process.argv;
if (!pkgDir || !exportName) {
  console.error('usage: gen-provider-capabilities.mjs <package-dir> <exportName>');
  process.exit(1);
}

const distPath = path.resolve(pkgDir, 'dist', 'protocol.js');
const mod = await import(pathToFileURL(distPath).href);
const capabilities = mod[exportName];
if (!capabilities) {
  console.error(`gen-provider-capabilities: export "${exportName}" not found in ${distPath}`);
  process.exit(1);
}

const outPath = path.resolve(pkgDir, 'capabilities.json');
writeFileSync(outPath, JSON.stringify(capabilities, null, 2) + '\n');
console.log(`wrote ${outPath}`);
