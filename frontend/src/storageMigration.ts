// One-shot localStorage key migration from the app's previous name. Every persisted
// browser preference was keyed `claude-ui.*` until the 2026-09 rename to agentic-ui;
// without this a returning browser would be logged out and lose theme/layout/etc.
//
// Runs as a side effect at import time and must be the FIRST import in main.tsx:
// api/rest.ts reads the token at module load, so the copy has to happen before that
// module evaluates. Idempotent — once the old keys are gone it does nothing.

const OLD_PREFIX = 'claude-ui.';
const NEW_PREFIX = 'agentic-ui.';

function migrate(): void {
  try {
    const oldKeys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith(OLD_PREFIX)) oldKeys.push(k);
    }
    for (const oldKey of oldKeys) {
      const newKey = NEW_PREFIX + oldKey.slice(OLD_PREFIX.length);
      // never clobber a value already written under the new name
      if (localStorage.getItem(newKey) === null) {
        const v = localStorage.getItem(oldKey);
        if (v !== null) localStorage.setItem(newKey, v);
      }
      localStorage.removeItem(oldKey);
    }
  } catch {
    // storage unavailable (private mode, blocked site data) — nothing to migrate
  }
}

migrate();
