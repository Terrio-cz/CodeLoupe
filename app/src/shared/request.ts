import type { Resource } from './contract';

export const API_BASE = '/ui-api/v1';

export type Query = Record<string, string | number | boolean | undefined>;

/** What the renderer may ask for; the main process validates it and builds the URL itself. */
export interface ApiRequest {
  resource: Resource;
  id?: string;
  query?: Query;
}

// Query keys each resource accepts (docs/ui-spec.md § 9). Anything else is refused, not dropped.
const QUERY_KEYS: Record<Resource, readonly string[]> = {
  nav: ['gapsSince'],
  overview: ['range'],
  worktrees: ['repo', 'layer', 'q'],
  'worktrees/:id': [],
  runs: ['range', 'role', 'task', 'worktree', 'gapsOnly', 'sort', 'order', 'limit', 'cursor'],
  'runs/:id': [],
  'runs/:id/steps': ['sort', 'order', 'flags', 'kind', 'around', 'limit', 'cursor'],
  tasks: ['project', 'state', 'q', 'limit', 'cursor'],
  'tasks/:id': [],
  index: [],
  gaps: ['range', 'tool', 'reason'],
  environment: [],
  settings: [],
  events: ['since', 'limit'],
};

const ID = /^[A-Za-z0-9._:-]{1,100}$/;
const MAX_VALUE = 200;

export type Validated = { ok: true; path: string; request: ApiRequest } | { ok: false; error: string };

/** Validates an untrusted request from the renderer and returns the daemon path for it. */
export function validateRequest(input: unknown): Validated {
  if (!input || typeof input !== 'object') return { ok: false, error: 'request must be an object' };
  const { resource, id, query } = input as Record<string, unknown>;
  if (typeof resource !== 'string' || !Object.prototype.hasOwnProperty.call(QUERY_KEYS, resource)) {
    return { ok: false, error: `unknown resource ${String(resource)}` };
  }
  const res = resource as Resource;
  const needsId = res.includes('/:id');
  if (needsId !== (id !== undefined)) return { ok: false, error: needsId ? 'id required' : 'id not allowed' };
  if (needsId && (typeof id !== 'string' || !ID.test(id))) return { ok: false, error: 'bad id' };

  const params = new URLSearchParams();
  const clean: Query = {};
  if (query !== undefined) {
    if (!query || typeof query !== 'object' || Array.isArray(query)) return { ok: false, error: 'query must be an object' };
    for (const [k, v] of Object.entries(query as Record<string, unknown>)) {
      if (v === undefined || v === '') continue;
      if (!QUERY_KEYS[res].includes(k)) return { ok: false, error: `query key ${k} not allowed for ${res}` };
      if (typeof v !== 'string' && typeof v !== 'number' && typeof v !== 'boolean') return { ok: false, error: `bad value for ${k}` };
      const s = String(v);
      if (s.length > MAX_VALUE) return { ok: false, error: `value of ${k} too long` };
      params.set(k, s);
      clean[k] = v;
    }
  }
  const segment = needsId ? res.replace(':id', encodeURIComponent(id as string)) : res;
  const qs = params.toString();
  return { ok: true, path: `${API_BASE}/${segment}${qs ? `?${qs}` : ''}`, request: { resource: res, id: id as string | undefined, query: clean } };
}
