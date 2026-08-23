import { useEffect, useMemo, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { placeholdersOf, type SkillInfo, type Template } from '../protocol';

export default function CreateSessionDialog({
  onCreated,
  onCancel,
}: {
  onCreated: (id: string) => void;
  onCancel: () => void;
}) {
  const [branches, setBranches] = useState<string[]>([]);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [name, setName] = useState('');
  const [branch, setBranch] = useState('');
  const [baseBranch, setBaseBranch] = useState('main');
  const [templateId, setTemplateId] = useState('');
  const [model, setModel] = useState('sonnet');
  const [permissionMode, setPermissionMode] = useState('default');
  const [thinking, setThinking] = useState('');
  const [effort, setEffort] = useState('');
  const [instructions, setInstructions] = useState('');
  const [ecosystemOn, setEcosystemOn] = useState(true);
  const [selectedSkills, setSelectedSkills] = useState<Set<string>>(new Set());
  const [extraSkill, setExtraSkill] = useState('');
  const [kickoffValues, setKickoffValues] = useState<Record<string, string>>({});
  const [advanced, setAdvanced] = useState('');

  useEffect(() => {
    api.branches().then(setBranches).catch(() => setBranches([]));
    api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
    api.skills().then(setSkills).catch(() => setSkills([]));
  }, []);

  const template = templates.find((t) => t.id === templateId);
  const kickoffPrompt = typeof template?.config['kickoffPrompt'] === 'string'
    ? (template.config['kickoffPrompt'] as string)
    : null;
  const placeholders = useMemo(() => (kickoffPrompt ? placeholdersOf(kickoffPrompt) : []), [kickoffPrompt]);

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const overrides: Record<string, unknown> = { model, permissionMode };
      if (thinking) overrides['thinking'] = thinking;
      if (effort) overrides['effort'] = effort;
      if (instructions.trim()) overrides['instructions'] = instructions.trim();
      if (!ecosystemOn) overrides['ecosystemPath'] = null;
      const skillSources = [
        ...[...selectedSkills].map((path) => ({ type: 'dir', ref: path })),
        ...(extraSkill.trim()
          ? [extraSkill.trim().startsWith('http') || extraSkill.trim().endsWith('.git')
              ? { type: 'repo', ref: extraSkill.trim() }
              : { type: 'dir', ref: extraSkill.trim() }]
          : []),
      ];
      if (skillSources.length > 0) overrides['skillSources'] = skillSources;
      if (advanced.trim()) Object.assign(overrides, JSON.parse(advanced) as Record<string, unknown>);

      const created = await api.createSession({
        name: name.trim() || branch.trim(),
        branch: branch.trim(),
        baseBranch,
        templateId: templateId || null,
        overrides,
        kickoffValues,
      });
      onCreated(created.id);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>New Session</h2>
        <div className="form-grid">
          <label>Name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="defaults to branch name" />

          <label>Branch</label>
          <input value={branch} onChange={(e) => setBranch(e.target.value)} list="branches" placeholder="feat/my-feature" autoFocus />
          <datalist id="branches">{branches.map((b) => <option key={b} value={b} />)}</datalist>

          <label>Base branch</label>
          <select value={baseBranch} onChange={(e) => setBaseBranch(e.target.value)}>
            {(branches.length ? branches : ['main']).map((b) => <option key={b} value={b}>{b}</option>)}
          </select>

          <label>Template</label>
          <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
            <option value="">— none —</option>
            {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>

          <label>Model</label>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            <option value="sonnet">sonnet</option>
            <option value="opus">opus</option>
            <option value="haiku">haiku</option>
          </select>

          <label>Permissions</label>
          <select value={permissionMode} onChange={(e) => setPermissionMode(e.target.value)}>
            <option value="default">ask for edits & commands</option>
            <option value="acceptEdits">auto-accept edits</option>
            <option value="plan">plan first</option>
          </select>

          <label>Thinking</label>
          <select value={thinking} onChange={(e) => setThinking(e.target.value)}>
            <option value="">provider default</option>
            <option value="off">off</option>
            <option value="adaptive">adaptive</option>
            <option value="16000">budget 16k</option>
          </select>

          <label>Effort</label>
          <select value={effort} onChange={(e) => setEffort(e.target.value)}>
            <option value="">provider default</option>
            {['low', 'medium', 'high', 'xhigh', 'max'].map((l) => <option key={l} value={l}>{l}</option>)}
          </select>

          <label>Ecosystem</label>
          <label style={{ color: 'var(--text)' }}>
            <input type="checkbox" checked={ecosystemOn} onChange={(e) => setEcosystemOn(e.target.checked)} />
            {' '}attach sibling services folder (read-only context)
          </label>

          {skills.length > 0 && (
            <>
              <label>Skills</label>
              <div className="skills-list">
                {skills.map((s) => (
                  <label key={s.path} title={s.description}>
                    <input
                      type="checkbox"
                      checked={selectedSkills.has(s.path)}
                      onChange={(e) => {
                        const next = new Set(selectedSkills);
                        if (e.target.checked) next.add(s.path);
                        else next.delete(s.path);
                        setSelectedSkills(next);
                      }}
                    />{' '}{s.name} <span style={{ color: 'var(--muted)' }}>{s.description}</span>
                  </label>
                ))}
              </div>
            </>
          )}

          <label>Extra skill</label>
          <input
            value={extraSkill}
            onChange={(e) => setExtraSkill(e.target.value)}
            placeholder="path or git URL of a skill source"
          />

          <label>Instructions</label>
          <textarea
            className="full"
            style={{ gridColumn: '2 / -1' }}
            rows={2}
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            placeholder="extra system instructions for this session"
          />

          {placeholders.map((ph) => (
            <div key={ph} style={{ display: 'contents' }}>
              <label>{ph}</label>
              <input
                value={kickoffValues[ph] ?? ''}
                onChange={(e) => setKickoffValues({ ...kickoffValues, [ph]: e.target.value })}
                placeholder={`kickoff value for {{${ph}}}`}
              />
            </div>
          ))}

          <label>Advanced</label>
          <textarea
            style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
            rows={2}
            value={advanced}
            onChange={(e) => setAdvanced(e.target.value)}
            placeholder='extra overrides JSON, e.g. {"maxTurns": 30, "contextDirs": ["/path"]}'
          />
        </div>
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !branch.trim()} onClick={() => void create()}>
            {busy ? 'Creating…' : 'Create'}
          </button>
        </div>
      </div>
    </div>
  );
}
