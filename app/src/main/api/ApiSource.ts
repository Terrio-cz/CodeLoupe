import type { ApiRequest } from '../../shared/request';

/** A source of screen data: the daemon's read-only API or the mock that follows the same contract. */
export interface ApiSource {
  readonly kind: 'mock' | 'daemon';
  get(req: ApiRequest, path: string): Promise<unknown>;
}
