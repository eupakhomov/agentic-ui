import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { TranscriptItem } from '../store/store';

export type PermissionResponse =
  | { behavior: 'allow'; updatedInput?: Record<string, unknown> }
  | { behavior: 'deny'; message?: string };

type PermissionItem = Extract<TranscriptItem, { kind: 'permission' }>;

export default function PermissionCard({
  item,
  onRespond,
}: {
  item: PermissionItem;
  onRespond: (response: PermissionResponse) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [edited, setEdited] = useState('');
  const isPlan = item.plan !== null;
  const resolved = item.decision !== null;

  if (isPlan) {
    return (
      <div className={`perm-card plan-card${resolved ? ' resolved' : ''}`}>
        <div className="head">
          {resolved ? (item.decision === 'allow' ? 'Plan approved' : 'Kept planning') : 'Plan ready for review'}
        </div>
        <div className="plan-body t-text">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{item.plan!}</ReactMarkdown>
        </div>
        {!resolved && (
          <div className="actions">
            <button className="primary" onClick={() => onRespond({ behavior: 'allow' })}>Approve &amp; execute</button>
            <button onClick={() => onRespond({ behavior: 'deny', message: 'Keep planning; do not execute yet.' })}>
              Keep planning
            </button>
          </div>
        )}
      </div>
    );
  }

  const bashCommand = item.toolName === 'Bash' && typeof item.input['command'] === 'string'
    ? (item.input['command'] as string)
    : null;

  const startEdit = () => {
    setEdited(bashCommand ?? JSON.stringify(item.input, null, 2));
    setEditing(true);
  };

  const approveEdited = () => {
    const updatedInput = bashCommand !== null
      ? { ...item.input, command: edited }
      : (JSON.parse(edited) as Record<string, unknown>);
    onRespond({ behavior: 'allow', updatedInput });
    setEditing(false);
  };

  return (
    <div className={`perm-card${resolved ? ' resolved' : ''}`}>
      <div className="head">
        {resolved
          ? `${item.toolName} — ${item.decision === 'allow' ? 'approved' : 'denied'}`
          : `Permission required: ${item.toolName}`}
      </div>
      {editing ? (
        <textarea
          style={{ width: '100%', minHeight: 70, fontFamily: 'monospace' }}
          value={edited}
          onChange={(e) => setEdited(e.target.value)}
        />
      ) : (
        <pre>{bashCommand ?? JSON.stringify(item.input, null, 2)}</pre>
      )}
      {!resolved && (
        <div className="actions">
          {editing ? (
            <>
              <button className="primary" onClick={approveEdited}>Approve edited</button>
              <button onClick={() => setEditing(false)}>Cancel edit</button>
            </>
          ) : (
            <>
              <button className="primary" onClick={() => onRespond({ behavior: 'allow' })}>Approve</button>
              <button onClick={startEdit}>Edit</button>
              <button className="danger" onClick={() => onRespond({ behavior: 'deny' })}>Deny</button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
