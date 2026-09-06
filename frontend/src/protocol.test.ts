import { describe, expect, it } from 'vitest';
import { placeholdersOf, assetStub } from './protocol';
import type { TemplateAsset } from './protocol';

// docs/plan/phase-9-production-hardening.md T6: protocol.ts's pure helpers.

describe('placeholdersOf', () => {
  it('extracts {{placeholder}} names in order', () => {
    expect(placeholdersOf('Fix {{ticket}} on {{branch}}')).toEqual(['ticket', 'branch']);
  });

  it('dedupes repeated placeholders', () => {
    expect(placeholdersOf('{{a}} and {{a}} again')).toEqual(['a']);
  });

  it('returns an empty array when there are none', () => {
    expect(placeholdersOf('no placeholders here')).toEqual([]);
  });

  it('ignores malformed braces', () => {
    expect(placeholdersOf('{{unterminated and {notbraces}')).toEqual([]);
  });
});

describe('assetStub', () => {
  it('maps a TemplateAsset into a LibraryAsset shape with placeholder fields blanked', () => {
    const template: TemplateAsset = { id: 'a1', kind: 'skill', name: 'my-skill', location: '/loc/a1', status: 'ACTIVE' };

    const stub = assetStub(template);

    expect(stub).toMatchObject({
      id: 'a1', sourceId: null, kind: 'skill', name: 'my-skill', location: '/loc/a1', status: 'ACTIVE',
      description: '', sourcePath: null, contentHash: '', tags: [], createdAt: '', updatedAt: '',
    });
  });
});
