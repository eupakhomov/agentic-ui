import type { ServicesResponse, SessionDetail, SessionEntity, SessionSummary, SkillInfo, Template } from '../protocol';

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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (authToken) headers['authorization'] = `Bearer ${authToken}`;
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  if (!res.ok) {
    const detail = parsed && typeof parsed['detail'] === 'string' ? (parsed['detail'] as string) : res.statusText;
    throw new ApiError(res.status, detail, parsed);
  }
  return parsed as T;
}

export const api = {
  listSessions: () => request<SessionSummary[]>('GET', '/api/sessions'),
  sessionDetail: (id: string) => request<SessionDetail>('GET', `/api/sessions/${id}`),
  createSession: (body: unknown) => request<SessionEntity>('POST', '/api/sessions', body),
  resumeSession: (id: string) => request<SessionEntity>('POST', `/api/sessions/${id}/resume`),
  closeSession: (id: string, dirty: string, commitMessage?: string) =>
    request<null>('DELETE', `/api/sessions/${id}?dirty=${dirty}${commitMessage ? `&commitMessage=${encodeURIComponent(commitMessage)}` : ''}`),
  deleteQueued: (id: string, pos: number) => request<null>('DELETE', `/api/sessions/${id}/queue/${pos}`),
  services: () => request<ServicesResponse>('GET', '/api/repo/services'),
  branches: (repo?: string) =>
    request<string[]>('GET', `/api/repo/branches${repo ? `?repo=${encodeURIComponent(repo)}` : ''}`),
  skills: () => request<SkillInfo[]>('GET', '/api/skills'),
  listTemplates: () => request<Template[]>('GET', '/api/templates'),
  createTemplate: (body: unknown) => request<Template>('POST', '/api/templates', body),
  updateTemplate: (id: string, body: unknown) => request<Template>('PUT', `/api/templates/${id}`, body),
  deleteTemplate: (id: string) => request<null>('DELETE', `/api/templates/${id}`),
};
