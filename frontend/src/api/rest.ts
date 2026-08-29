import type { AssetKind, FilledMeta, ImportItemResult, LibraryAsset, LibrarySearchHit, LibrarySource, ScanResult, ServicesResponse, SessionDetail, SessionEntity, SessionSummary, Settings, SkillInfo, StaleSession, Template, TicketSummary, TurnUsage } from '../protocol';

let authToken: string | null = localStorage.getItem('claude-ui.token');

export function setToken(token: string | null): void {
  authToken = token;
  if (token) localStorage.setItem('claude-ui.token', token);
  else localStorage.removeItem('claude-ui.token');
}

export function token(): string | null {
  return authToken;
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public body: Record<string, unknown> | null) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (authToken) headers['authorization'] = `Bearer ${authToken}`;
  console.debug('[claude-ui] api request', method, path, body ?? '');
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal });
  } catch (e) {
    // network failure, CORS, or abort — never an ApiError since there's no HTTP response to read
    console.error('[claude-ui] api request failed (network/abort)', method, path, e);
    throw e;
  }
  const text = await res.text();
  let parsed: Record<string, unknown> | null;
  try {
    parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch (e) {
    console.error('[claude-ui] api response was not valid JSON', method, path, res.status, text.slice(0, 500));
    throw e;
  }
  if (!res.ok) {
    const detail = parsed && typeof parsed['detail'] === 'string' ? (parsed['detail'] as string) : res.statusText;
    console.error('[claude-ui] api error', method, path, res.status, detail, parsed);
    throw new ApiError(res.status, detail, parsed);
  }
  console.debug('[claude-ui] api response', method, path, res.status);
  return parsed as T;
}

export const api = {
  raw: <T>(method: string, path: string, body?: unknown) => request<T>(method, path, body),
  listSessions: () => request<SessionSummary[]>('GET', '/api/sessions'),
  sessionDetail: (id: string) => request<SessionDetail>('GET', `/api/sessions/${id}`),
  createSession: (body: unknown) => request<SessionEntity>('POST', '/api/sessions', body),
  resumeSession: (id: string) => request<SessionEntity>('POST', `/api/sessions/${id}/resume`),
  closeSession: (id: string, dirty: string, commitMessage?: string) =>
    request<null>('DELETE', `/api/sessions/${id}?dirty=${dirty}${commitMessage ? `&commitMessage=${encodeURIComponent(commitMessage)}` : ''}`),
  deleteQueued: (id: string, pos: number) => request<null>('DELETE', `/api/sessions/${id}/queue/${pos}`),
  patchSession: (id: string, body: { costBudgetUsd?: number; name?: string }) =>
    request<SessionEntity>('PATCH', `/api/sessions/${id}`, body),
  services: () => request<ServicesResponse>('GET', '/api/repo/services'),
  branches: (repo?: string) =>
    request<string[]>('GET', `/api/repo/branches${repo ? `?repo=${encodeURIComponent(repo)}` : ''}`),
  skills: () => request<SkillInfo[]>('GET', '/api/skills'),
  listTemplates: () => request<Template[]>('GET', '/api/templates'),
  createTemplate: (body: unknown) => request<Template>('POST', '/api/templates', body),
  updateTemplate: (id: string, body: unknown) => request<Template>('PUT', `/api/templates/${id}`, body),
  deleteTemplate: (id: string) => request<null>('DELETE', `/api/templates/${id}`),
  ticketImportEnabled: () => request<{ enabled: boolean }>('GET', '/api/tickets/import/enabled'),
  importTicket: (ticketRef: string, signal?: AbortSignal) =>
    request<{ branchName: string; prompt: string; recommendedModel: string | null; ticketRef: string | null }>(
      'POST', '/api/tickets/import', { ticketRef }, signal),
  listRecentTickets: (signal?: AbortSignal) =>
    request<TicketSummary[]>('POST', '/api/tickets/recent', undefined, signal),
  getSettings: () => request<Settings>('GET', '/api/settings'),
  updateSettings: (patch: Partial<Pick<Settings, 'linearOAuthEnabled' | 'ticketImportSpec' | 'ecosystemRoot'
    | 'prChecksEnabled' | 'prCheckPollIntervalSeconds' | 'librarySkillsRoot' | 'libraryAgentsRoot'
    | 'libraryVectorize' | 'librarySyncEnabled' | 'librarySyncIntervalMinutes'>>) =>
    request<Settings>('PATCH', '/api/settings', patch),
  libraryScan: (type: 'dir' | 'repo', ref: string, signal?: AbortSignal) =>
    request<ScanResult>('POST', '/api/library/scan', { type, ref }, signal),
  libraryImport: (source: { type: 'dir' | 'repo'; ref: string; syncEnabled: boolean },
    items: { path: string; kind: AssetKind; name: string; description: string; tags: string[] }[]) =>
    request<ImportItemResult[]>('POST', '/api/library/import', { source, items }),
  libraryAiFill: (type: 'dir' | 'repo', ref: string, paths: string[], signal?: AbortSignal) =>
    request<FilledMeta[]>('POST', '/api/library/ai-fill', { type, ref, paths }, signal),
  libraryAssets: (filters?: { kind?: string; status?: string; q?: string }) => {
    const params = new URLSearchParams();
    if (filters?.kind) params.set('kind', filters.kind);
    if (filters?.status) params.set('status', filters.status);
    if (filters?.q) params.set('q', filters.q);
    const qs = params.toString();
    return request<LibraryAsset[]>('GET', `/api/library/assets${qs ? `?${qs}` : ''}`);
  },
  libraryUpdateAsset: (id: string, patch: { name?: string; description?: string; tags?: string[]; status?: string }) =>
    request<LibraryAsset>('PATCH', `/api/library/assets/${id}`, patch),
  libraryDeleteAsset: (id: string) => request<null>('DELETE', `/api/library/assets/${id}`),
  librarySearch: (q: string, k = 10) =>
    request<LibrarySearchHit[]>('GET', `/api/library/search?q=${encodeURIComponent(q)}&k=${k}`),
  librarySources: () => request<LibrarySource[]>('GET', '/api/library/sources'),
  libraryUpdateSource: (id: string, patch: { syncEnabled?: boolean }) =>
    request<LibrarySource>('PATCH', `/api/library/sources/${id}`, patch),
  librarySyncNow: (id: string) => request<LibrarySource>('POST', `/api/library/sources/${id}/sync`),
  libraryDeleteSource: (id: string) => request<null>('DELETE', `/api/library/sources/${id}`),
  libraryDismiss: (id: string, paths?: string[]) =>
    request<LibrarySource>('POST', `/api/library/sources/${id}/discoveries/dismiss`, paths ? { paths } : {}),
  usage: (months = 6) => request<TurnUsage[]>('GET', `/api/usage?months=${months}`),
  staleSessions: () => request<StaleSession[]>('GET', '/api/usage/stale-sessions'),
};
