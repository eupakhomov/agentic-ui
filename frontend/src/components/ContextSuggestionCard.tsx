import { Compact } from '../icons';

/** Fires once per warn-threshold crossing (re-armed by a compaction) — see
 * store.ts's evaluateContextWarn. Styled like PermissionCard (.perm-card / --warn-bg /
 * .head / .actions), not a transcript item — dismissing it doesn't leave a scrollback
 * entry the way a permission decision does. */
export default function ContextSuggestionCard({
  percentage,
  canCompact,
  compacting,
  onCompact,
  onDismiss,
}: {
  percentage: number;
  canCompact: boolean;
  compacting: boolean;
  onCompact: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="perm-card ctx-suggestion">
      <div className="head">Context is {percentage}% full — compact now to keep costs down</div>
      <div className="actions">
        {canCompact && (
          <button
            className={`primary with-icon${compacting ? ' pulse' : ''}`}
            disabled={compacting}
            onClick={onCompact}
            title="compact this session's context in place"
          >
            <Compact />Compact
          </button>
        )}
        <button onClick={onDismiss}>Dismiss</button>
      </div>
    </div>
  );
}
