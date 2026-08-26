import { useState } from 'react';
import type { TranscriptItem } from '../store/store';
import type { PermissionResponse } from './PermissionCard';

type PermissionItem = Extract<TranscriptItem, { kind: 'permission' }>;

interface QuestionOption {
  label: string;
  description?: string;
  preview?: string;
}

interface Question {
  question: string;
  header: string;
  options: QuestionOption[];
  multiSelect?: boolean;
}

export default function AskUserQuestionCard({
  item,
  onRespond,
}: {
  item: PermissionItem;
  onRespond: (response: PermissionResponse) => void;
}) {
  const questions = (item.input['questions'] as Question[] | undefined) ?? [];
  const resolved = item.decision !== null;
  const finalAnswers = (item.input['answers'] as Record<string, string> | undefined) ?? null;

  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const [otherOn, setOtherOn] = useState<Record<string, boolean>>({});
  const [otherText, setOtherText] = useState<Record<string, string>>({});

  const toggleOption = (q: Question, label: string) => {
    setSelected((prev) => {
      const current = prev[q.question] ?? [];
      const next = q.multiSelect
        ? (current.includes(label) ? current.filter((l) => l !== label) : [...current, label])
        : [label];
      return { ...prev, [q.question]: next };
    });
    if (!q.multiSelect) setOtherOn((prev) => ({ ...prev, [q.question]: false }));
  };

  const enableOther = (q: Question) => {
    setOtherOn((prev) => ({ ...prev, [q.question]: true }));
    if (!q.multiSelect) setSelected((prev) => ({ ...prev, [q.question]: [] }));
  };

  const answeredFor = (q: Question) =>
    (selected[q.question] ?? []).length > 0 || (otherOn[q.question] && !!otherText[q.question]?.trim());

  const allAnswered = questions.length > 0 && questions.every(answeredFor);

  const submit = () => {
    const answers: Record<string, string> = {};
    for (const q of questions) {
      const picks = selected[q.question] ?? [];
      const other = otherOn[q.question] ? otherText[q.question]?.trim() : '';
      answers[q.question] = [...picks, ...(other ? [other] : [])].join(', ');
    }
    onRespond({ behavior: 'allow', updatedInput: { ...item.input, answers } });
  };

  const skip = () => onRespond({ behavior: 'deny', message: 'User skipped answering; proceed with your best judgment.' });

  return (
    <div className={`perm-card ask-card${resolved ? ' resolved' : ''}`}>
      <div className="head">{resolved ? (item.decision === 'allow' ? 'Answered' : 'Skipped') : 'Question'}</div>
      {questions.map((q) => (
        <div className="ask-question" key={q.question}>
          <span className="ask-chip">{q.header}</span>
          <div className="ask-text">{q.question}</div>
          {resolved ? (
            <div className="ask-answer">{finalAnswers?.[q.question] || '—'}</div>
          ) : (
            <div className="ask-options">
              {q.options.map((opt) => {
                const isSelected = (selected[q.question] ?? []).includes(opt.label);
                return (
                  <label className={`ask-option${isSelected ? ' selected' : ''}`} key={opt.label} title={opt.preview}>
                    <input
                      type={q.multiSelect ? 'checkbox' : 'radio'}
                      name={q.question}
                      checked={isSelected}
                      onChange={() => toggleOption(q, opt.label)}
                    />
                    <span>
                      <span className="ask-label">{opt.label}</span>
                      {opt.description && <span className="ask-desc"> — {opt.description}</span>}
                    </span>
                  </label>
                );
              })}
              <label className={`ask-option${otherOn[q.question] ? ' selected' : ''}`}>
                <input
                  type={q.multiSelect ? 'checkbox' : 'radio'}
                  name={q.question}
                  checked={!!otherOn[q.question]}
                  onChange={() => enableOther(q)}
                />
                <span className="ask-label">Other:</span>
                <input
                  className="ask-other-input"
                  value={otherText[q.question] ?? ''}
                  onFocus={() => enableOther(q)}
                  onChange={(e) => {
                    enableOther(q);
                    setOtherText((prev) => ({ ...prev, [q.question]: e.target.value }));
                  }}
                  placeholder="type your own answer…"
                />
              </label>
            </div>
          )}
        </div>
      ))}
      {!resolved && (
        <div className="actions">
          <button className="primary" onClick={submit} disabled={!allAnswered}>Submit answers</button>
          <button onClick={skip}>Skip</button>
        </div>
      )}
    </div>
  );
}
