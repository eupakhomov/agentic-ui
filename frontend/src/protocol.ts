// Mirrors docs/PROTOCOL.md (WS envelope + payloads) and the REST DTOs.

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

export type SessionState =
  | 'CREATING' | 'PROVISIONING' | 'STARTING' | 'IDLE' | 'RUNNING'
  | 'WAITING_INPUT' | 'PARKED' | 'CRASHED' | 'CLOSING' | 'CLOSED' | 'FAILED';

export interface Capabilities {
  permissionModes: PermissionMode[];
  thinking: boolean;
  effort: boolean;
  planMode: boolean;
  resume: boolean;
  skills: boolean;
  agents: boolean;
  mcp: boolean;
  interrupt: boolean;
  fallbackModel: boolean;
  updatedInput: boolean;
  modelSwitch: boolean;
}

/** GET /api/providers — static per-provider capabilities, used to gate create-dialog/
 * template controls before any session exists (see ProviderController.java). */
export interface ProviderView {
  id: string;
  capabilities: Capabilities;
}

/** Journal envelope arriving over the WebSocket. */
export interface Envelope {
  seq: number;
  ts?: string;
  type: string;
  payload: Record<string, unknown> & { [k: string]: unknown };
}

export interface SessionEntity {
  id: string;
  name: string;
  provider: string;
  repoPath: string;
  branch: string;
  baseBranch: string;
  worktreePath: string;
  providerSessionId: string | null;
  capabilities: Capabilities | null;
  model: string | null;
  permissionMode: PermissionMode;
  ecosystemPath: string | null;
  contextDirs: string[];
  thinking: string | null;
  effort: string | null;
  costBudgetUsd: number | null;
  state: SessionState;
  kickoffPrompt: string | null;
  /** 'user' (default) or 'system' — backend-initiated tasks, hidden by default in the dashboard */
  kind: 'user' | 'system';
  /** Canonical ticket identifier (e.g. "ENG-123") if this session was created via ticket import */
  ticketRef: string | null;
  /** GitHub PR URL opened from this session's branch, if any; one PR tracked per session */
  prUrl: string | null;
  prCheckStatus: PrCheckStatus | null;
  prCheckedAt: string | null;
}

export type PrCheckStatus = 'PENDING' | 'SUCCESS' | 'FAILURE' | 'MERGED' | 'CLOSED' | 'ERROR';

export interface SessionSummary {
  id: string;
  name: string;
  provider: string;
  branch: string;
  model: string | null;
  permissionMode: PermissionMode;
  state: SessionState;
  kind: 'user' | 'system';
  costToDate: number;
}

export interface QueuedMessage {
  pos: number;
  text: string;
}

export interface SessionDetail {
  session: SessionEntity;
  queued: QueuedMessage[];
  lastSeq: number;
  costToDate: number;
}

/** A template's live reference to a library asset (Phase 5.4) — resolved for display. */
export interface TemplateAsset {
  id: string;
  kind: AssetKind;
  name: string;
  location: string;
  status: AssetStatus;
}

export interface Template {
  id: string;
  name: string;
  description: string | null;
  config: Record<string, unknown>;
  assets: TemplateAsset[];
}

export interface ServiceInfo {
  name: string;
  path: string;
}

export interface ServicesResponse {
  ecosystemRoot: string;
  defaultRepoPath: string;
  services: ServiceInfo[];
}

export interface Settings {
  linearOAuthEnabled: boolean;
  ticketImportSpec: string;
  linearApiKeyConfigured: boolean;
  ecosystemRoot: string;
  prChecksEnabled: boolean;
  prCheckPollIntervalSeconds: number;
  librarySkillsRoot: string;
  libraryAgentsRoot: string;
  libraryVectorize: boolean;
  librarySyncEnabled: boolean;
  librarySyncIntervalMinutes: number;
  voyageConfigured: boolean;
  defaultProvider: string;
  /** JSON: {"<model>"|"default": {"inputPer1M":n, "cachedInputPer1M":n, "outputPer1M":n}} */
  codexPricing: string;
}

// --- skill & agent library ---

export type AssetKind = 'skill' | 'agent';
export type AssetStatus = 'ACTIVE' | 'ARCHIVED';

export interface LibraryAsset {
  id: string;
  sourceId: string | null;
  kind: AssetKind;
  name: string;
  description: string;
  location: string;
  sourcePath: string | null;
  contentHash: string;
  status: AssetStatus;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ScanCandidate {
  path: string;
  kind: AssetKind;
  confidence: 'high' | 'low';
  name: string;
  description: string;
  hash: string;
  sizeBytes: number;
  alreadyImported: boolean;
  changedSinceImport: boolean;
}

export interface ScanResult {
  type: 'dir' | 'repo';
  ref: string;
  candidates: ScanCandidate[];
}

export interface ImportItemResult {
  path: string;
  assetId: string | null;
  warning: string | null;
}

export interface FilledMeta {
  path: string;
  name: string;
  description: string;
  tags: string[];
}

export interface LibraryDiscovery {
  path: string;
  kind: AssetKind;
  firstSeenAt: string;
}

export interface LibrarySource {
  id: string;
  type: 'dir' | 'repo';
  ref: string;
  syncEnabled: boolean;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null;
  lastSyncError: string | null;
  assetCount: number;
  discoveries: LibraryDiscovery[];
}

export interface LibrarySearchHit {
  asset: LibraryAsset;
  distance: number;
}

export interface LibraryAssetContent {
  content: string;
  truncated: boolean;
  sourceFile: string;
}

export interface TicketSummary {
  ref: string;
  title: string;
  status: string;
}

export interface TurnUsage {
  sessionId: string;
  sessionName: string;
  ts: string;
  model: string | null;
  costUsd: number;
}

export interface StaleSession {
  id: string;
  name: string;
  branch: string;
  state: SessionState;
  updatedAt: string;
  worktreeExists: boolean;
  dirty: boolean;
}

export const LIVE_STATES: SessionState[] = ['STARTING', 'IDLE', 'RUNNING', 'WAITING_INPUT'];

/** Extract {{placeholders}} from a kickoff prompt template. */
export function placeholdersOf(prompt: string): string[] {
  return [...new Set([...prompt.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]!))];
}

/** A template's resolved asset link, shaped as a (placeholder-filled) LibraryAsset for reuse in AssetPickerDialog. */
export function assetStub(a: TemplateAsset): LibraryAsset {
  return {
    id: a.id, sourceId: null, kind: a.kind, name: a.name, description: '', location: a.location,
    sourcePath: null, contentHash: '', status: a.status, tags: [], createdAt: '', updatedAt: '',
  };
}
