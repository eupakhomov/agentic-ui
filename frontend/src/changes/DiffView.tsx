import { useEffect, useRef } from 'react';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { MergeView, unifiedMergeView } from '@codemirror/merge';
import { loadLanguage } from './language';
import { selectionFromState, type LineSelection } from './selection';
import { appEditorTheme } from './theme';

export type DiffMode = 'unified' | 'split';

interface Props {
  path: string;
  /** merge-base content ('' for an added file) */
  original: string;
  /** working-tree content ('' for a deleted file) */
  modified: string;
  mode: DiffMode;
  onSelection: (sel: LineSelection | null) => void;
  /** Enter while something is selected */
  onAsk: () => void;
}

const COLLAPSE = { margin: 3, minSize: 4 };

/**
 * One read-only CodeMirror diff. Rebuilt whenever the file, its content or the mode changes — the
 * documents are small and read-only, so there is no state worth preserving across a rebuild.
 * Unified shows the working tree with deleted chunks inline (only the new side is selectable);
 * split shows both sides as editors, so lines of the old version can be asked about too.
 */
export default function DiffView({ path, original, modified, mode, onSelection, onAsk }: Props) {
  const hostRef = useRef<HTMLDivElement>(null);
  const onSelectionRef = useRef(onSelection);
  onSelectionRef.current = onSelection;
  const onAskRef = useRef(onAsk);
  onAskRef.current = onAsk;

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let cancelled = false;
    let destroy: (() => void) | null = null;
    // a wholly added/deleted file is one solid block; per-word highlighting on top of it is just noise
    const wholeFile = original === '' || modified === '';

    void loadLanguage(path).then((lang) => {
      if (cancelled) return;
      const common = (side: LineSelection['side']): Extension[] => [
        lineNumbers(),
        EditorState.readOnly.of(true),
        keymap.of([
          {
            key: 'Enter',
            run: (view) => {
              if (view.state.selection.main.empty) return false;
              onAskRef.current();
              return true;
            },
          },
          ...defaultKeymap,
        ]),
        appEditorTheme,
        EditorView.updateListener.of((u) => {
          if (u.selectionSet || u.focusChanged) {
            if (u.view.hasFocus || !u.state.selection.main.empty) {
              onSelectionRef.current(selectionFromState(u.state, side));
            }
          }
        }),
        ...(lang ? [lang] : []),
      ];

      if (mode === 'split') {
        const mv = new MergeView({
          parent: host,
          a: { doc: original, extensions: common('base') },
          b: { doc: modified, extensions: common('new') },
          highlightChanges: !wholeFile,
          gutter: true,
          collapseUnchanged: COLLAPSE,
        });
        destroy = () => mv.destroy();
      } else {
        const view = new EditorView({
          parent: host,
          doc: modified,
          extensions: [
            ...common('new'),
            unifiedMergeView({
              original,
              mergeControls: false,
              gutter: true,
              highlightChanges: !wholeFile,
              syntaxHighlightDeletions: true,
              collapseUnchanged: COLLAPSE,
            }),
          ],
        });
        destroy = () => view.destroy();
      }
    });

    return () => {
      cancelled = true;
      destroy?.();
      onSelectionRef.current(null);
    };
  }, [path, original, modified, mode]);

  return <div className="changes-diff-host" ref={hostRef} />;
}
