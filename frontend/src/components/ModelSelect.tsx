import type { Capabilities } from '../protocol';

/**
 * A model picker driven by the active provider's capabilities (see P3,
 * docs/plan/phase-9-production-hardening.md): a `<select>` of `capabilities.models` when the
 * provider has a fixed catalog, or free text when it doesn't (e.g. Codex — no hardcoded model
 * list, docs/plan/phase-5.13-codex-provider.md).
 */
export default function ModelSelect({
  value,
  onChange,
  capabilities,
  providerDefaultLabel = 'provider default',
}: {
  value: string;
  onChange: (value: string) => void;
  capabilities: Capabilities | undefined;
  providerDefaultLabel?: string;
}) {
  const models = capabilities?.models ?? [];
  if (models.length === 0) {
    return (
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={`${providerDefaultLabel} — leave blank`}
      />
    );
  }
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{providerDefaultLabel}</option>
      {models.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
    </select>
  );
}
