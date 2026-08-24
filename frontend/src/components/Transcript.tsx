import { memo, useEffect, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { TranscriptItem } from '../store/store';
import PermissionCard, { type PermissionResponse } from './PermissionCard';

const Markdown = memo(function Markdown({ text }: { text: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>;
});

function summarizeInput(input: unknown): string {
  const s = JSON.stringify(input) ?? '';
  return s.length > 120 ? s.slice(0, 120) + '…' : s;
}

export default function Transcript({
  items,
  onPermission,
}: {
  items: TranscriptItem[];
  onPermission: (requestId: string, response: PermissionResponse) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);

  useEffect(() => {
    const el = ref.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [items]);

  return (
    <div
      className="transcript"
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget;
        stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
      }}
    >
      {items.map((item, i) => {
        switch (item.kind) {
          case 'user':
            return <div key={i} className="t-user">{item.text}</div>;
          case 'text':
            return <div key={i} className="t-text"><Markdown text={item.text} /></div>;
          case 'thinking':
            return (
              <details key={i} className="t-thinking">
                <summary>
                  {item.done ? 'thought' : 'thinking…'}
                  {item.estimatedTokens > 0 ? ` (~${item.estimatedTokens} tokens)` : ''}
                </summary>
                <div className="body">{item.text}</div>
              </details>
            );
          case 'tool':
            return (
              <details key={i} className={`t-tool${item.isError ? ' error' : ''}`}>
                <summary>
                  <span className="tname">{item.name}</span>{' '}
                  {summarizeInput(item.input)}
                  {item.output === undefined ? ' ⏳' : item.isError ? ' ✗' : ' ✓'}
                </summary>
                <pre>
                  {JSON.stringify(item.input, null, 2)}
                  {item.output !== undefined ? `\n─── result${item.truncated ? ' (truncated)' : ''} ───\n${item.output}` : ''}
                </pre>
              </details>
            );
          case 'permission':
            return (
              <PermissionCard
                key={item.requestId}
                item={item}
                onRespond={(response) => onPermission(item.requestId, response)}
              />
            );
          case 'turn_footer':
            return (
              <div key={i} className="t-footer">
                {item.stopReason} · ${item.costUsd.toFixed(4)} · {(item.durationMs / 1000).toFixed(1)}s
                {item.model ? ` · ${item.model}` : ''}
              </div>
            );
          case 'note':
            return <div key={i} className={`t-note ${item.level}`}>{item.text}</div>;
        }
      })}
    </div>
  );
}
