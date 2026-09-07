import { useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { TicketSummary } from '../protocol';

export interface TicketImportOutcome {
  branchName: string;
  prompt: string;
  ticketRef: string | null;
  /** null unless it's one of `validModelIds` — see ModelCatalog/TicketImportService (P3). */
  recommendedModel: string | null;
}

/**
 * The import-ticket/browse-recent/abort-controller dance shared by CreateSessionDialog and
 * QuickSessionDialog (see G5, docs/plan/phase-9-production-hardening.md). `validModelIds` filters
 * a ticket's recommendedModel against the active provider's actual model catalog — callers own
 * everything else about what happens with a successful import (which local fields it fills).
 */
export function useTicketImport(validModelIds: string[]) {
  const [ticketRef, setTicketRef] = useState('');
  const [importBusy, setImportBusy] = useState(false);
  const [importError, setImportError] = useState('');
  const importAbortRef = useRef<AbortController | null>(null);
  const [showTicketPicker, setShowTicketPicker] = useState(false);
  const [recentTickets, setRecentTickets] = useState<TicketSummary[] | null>(null);
  const [pickerBusy, setPickerBusy] = useState(false);
  const [pickerError, setPickerError] = useState('');
  const pickerAbortRef = useRef<AbortController | null>(null);

  const importTicket = async (refOverride: string | undefined, onResult: (outcome: TicketImportOutcome) => void) => {
    const ref = (refOverride ?? ticketRef).trim();
    setImportError('');
    setImportBusy(true);
    const controller = new AbortController();
    importAbortRef.current = controller;
    // backend already bounds the underlying system-session turn to 45s (see
    // SessionService.runSystemTurn / TicketImportService) and always resolves with a real error
    // by then; this is a client-side safety net so the button can never get stuck forever even if
    // that assumption turns out wrong in some environment
    const safetyNet = setTimeout(() => controller.abort('timeout'), 50_000);
    if (import.meta.env.DEV) console.log('[claude-ui] ticket import: fetching', ref);
    const started = performance.now();
    try {
      const result = await api.importTicket(ref, controller.signal);
      if (import.meta.env.DEV) console.log('[claude-ui] ticket import: succeeded in', Math.round(performance.now() - started), 'ms', result);
      onResult({
        branchName: result.branchName,
        prompt: result.prompt,
        ticketRef: result.ticketRef,
        recommendedModel: result.recommendedModel && validModelIds.includes(result.recommendedModel)
          ? result.recommendedModel : null,
      });
    } catch (e) {
      const elapsed = Math.round(performance.now() - started);
      if (controller.signal.aborted) {
        console.error('[claude-ui] ticket import: aborted after', elapsed, 'ms, reason:', controller.signal.reason);
        setImportError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        console.error('[claude-ui] ticket import: failed after', elapsed, 'ms', e);
        setImportError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      clearTimeout(safetyNet);
      importAbortRef.current = null;
      setImportBusy(false);
    }
  };

  const browseRecentTickets = async () => {
    setPickerError('');
    setPickerBusy(true);
    setShowTicketPicker(true);
    const controller = new AbortController();
    pickerAbortRef.current = controller;
    const safetyNet = setTimeout(() => controller.abort('timeout'), 50_000);
    if (import.meta.env.DEV) console.log('[claude-ui] ticket browse: fetching recent tickets');
    const started = performance.now();
    try {
      const list = await api.listRecentTickets(controller.signal);
      if (import.meta.env.DEV) console.log('[claude-ui] ticket browse: succeeded in', Math.round(performance.now() - started), 'ms', list);
      setRecentTickets(list);
    } catch (e) {
      const elapsed = Math.round(performance.now() - started);
      if (controller.signal.aborted) {
        console.error('[claude-ui] ticket browse: aborted after', elapsed, 'ms, reason:', controller.signal.reason);
        setPickerError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        console.error('[claude-ui] ticket browse: failed after', elapsed, 'ms', e);
        setPickerError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      clearTimeout(safetyNet);
      pickerAbortRef.current = null;
      setPickerBusy(false);
    }
  };

  const pickTicket = (ref: string, onResult: (outcome: TicketImportOutcome) => void) => {
    setShowTicketPicker(false);
    setTicketRef(ref);
    void importTicket(ref, onResult);
  };

  const cancel = () => {
    importAbortRef.current?.abort('user');
    pickerAbortRef.current?.abort('user');
  };

  return {
    ticketRef, setTicketRef,
    importBusy, importError,
    showTicketPicker, setShowTicketPicker,
    recentTickets, pickerBusy, pickerError,
    importTicket, browseRecentTickets, pickTicket, cancel,
  };
}
