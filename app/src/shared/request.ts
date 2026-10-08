import type { Resource } from './contract';

export const API_BASE = '/ui-api/v1';

export type Query = Record<string, string | number | boolean | undefined>;

/** What the renderer may ask for; the main process validates it and builds the URL itself. */
export interface ApiRequest {
  resource: Resource;
  id?: string;
  query?: Query;
}

interface ResourceSpec {
  /** Daemon path; `:id` is replaced by the validated id. */
  path: string;
  /** Query keys the resource accepts. */
  query: readonly string[];
}

const ui = (name: string, query: readonly string[] = []): ResourceSpec => ({ path: `${API_BASE}/${name}`, query });
/** A read-only route of the daemon outside the UI API (status, workspaces, jobs, …). */
const daemon = (path: string, query: readonly string[] = []): ResourceSpec => ({ path, query });

// Every GET the renderer may ask for, with the query keys each accepts (docs/ui-spec.md § 9). Anything else is refused, not dropped.
const SPECS: Record<Resource, ResourceSpec> = {
  nav: ui('nav', ['gapsSince']),
  overview: ui('overview', ['range']),
  worktrees: ui('worktrees', ['repo', 'layer', 'q']),
  'worktrees/:id': ui('worktrees/:id'),
  tasks: ui('tasks', ['project', 'state', 'q', 'limit', 'cursor']),
  'tasks/:id': ui('tasks/:id'),
  index: ui('index'),
  gaps: ui('gaps', ['range', 'tool', 'reason']),
  environment: ui('environment'),
  'environment/audit': ui('environment/audit', ['name', 'scope', 'limit']),
  settings: ui('settings'),
  events: ui('events', ['since', 'limit']),
  runs: ui('runs', ['range', 'sort', 'role', 'q', 'limit', 'cursor']),
  'runs/:id': ui('runs/:id'),
  'runs/:id/steps': ui('runs/:id/steps', ['sort', 'limit', 'cursor']),
  'status/history': daemon('/status/history'),
  workspaces: daemon('/workspaces', ['repo', 'size']),
  resources: daemon('/resources', ['stats']),
  reconcile: daemon('/reconcile'),
  releases: daemon('/workspaces/releases'),
  ports: daemon('/ports'),
  status: daemon('/status'),
  jobs: daemon('/jobs', ['limit']),
  'jobs/:id': daemon('/jobs/:id'),
  webhooks: daemon('/webhooks'),
  deliveries: daemon('/webhooks/deliveries', ['limit']),
};

// No `.` or `..` alone: the daemon would normalise them into another path.
const ID = /^(?!\.{1,2}$)[A-Za-z0-9._:-]{1,100}$/;
const MAX_VALUE = 200;

/** How long the daemon may take: a reading that walks files (`size`) or asks Docker for each container (`stats`) takes seconds on a big repository. */
export function timeoutMs(req: ApiRequest): number {
  return req.query?.size || req.query?.stats ? 120_000 : 5_000;
}

export type Validated = { ok: true; path: string; request: ApiRequest } | { ok: false; error: string };

/** Validates an untrusted request from the renderer and returns the daemon path for it. */
export function validateRequest(input: unknown): Validated {
  if (!input || typeof input !== 'object') return { ok: false, error: 'request must be an object' };
  const { resource, id, query } = input as Record<string, unknown>;
  if (typeof resource !== 'string' || !Object.prototype.hasOwnProperty.call(SPECS, resource)) {
    return { ok: false, error: `unknown resource ${String(resource)}` };
  }
  const res = resource as Resource;
  const spec = SPECS[res];
  const needsId = spec.path.includes(':id');
  if (needsId !== (id !== undefined)) return { ok: false, error: needsId ? 'id required' : 'id not allowed' };
  if (needsId && (typeof id !== 'string' || !ID.test(id))) return { ok: false, error: 'bad id' };

  const params = new URLSearchParams();
  const clean: Query = {};
  if (query !== undefined) {
    if (!query || typeof query !== 'object' || Array.isArray(query)) return { ok: false, error: 'query must be an object' };
    for (const [k, v] of Object.entries(query as Record<string, unknown>)) {
      if (v === undefined || v === '') continue;
      if (!spec.query.includes(k)) return { ok: false, error: `query key ${k} not allowed for ${res}` };
      if (typeof v !== 'string' && typeof v !== 'number' && typeof v !== 'boolean') return { ok: false, error: `bad value for ${k}` };
      const s = String(v);
      if (s.length > MAX_VALUE) return { ok: false, error: `value of ${k} too long` };
      params.set(k, s);
      clean[k] = v;
    }
  }
  const base = needsId ? spec.path.replace(':id', encodeURIComponent(id as string)) : spec.path;
  const qs = params.toString();
  return { ok: true, path: `${base}${qs ? `?${qs}` : ''}`, request: { resource: res, id: id as string | undefined, query: clean } };
}
