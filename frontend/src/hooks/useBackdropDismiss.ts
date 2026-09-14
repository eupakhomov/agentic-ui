import { useRef, type MouseEventHandler } from 'react';

/**
 * Spread onto a `.modal-backdrop` element in place of a plain `onClick={close}`.
 * Closes only when the mousedown that led to the click also started on the backdrop
 * itself — otherwise a drag begun inside the modal (resizing a textarea, selecting
 * text) that happens to release over the backdrop would count as "click outside" and
 * close the dialog, even though nothing about the drag targeted the backdrop.
 */
export function useBackdropDismiss(close: () => void): {
  onMouseDown: MouseEventHandler;
  onClick: MouseEventHandler;
} {
  const mouseDownOnBackdrop = useRef(false);
  return {
    onMouseDown: (e) => { mouseDownOnBackdrop.current = e.target === e.currentTarget; },
    onClick: () => { if (mouseDownOnBackdrop.current) close(); },
  };
}
