import type { ApiRequest } from '../../shared/request';
import type { DaemonClient } from '../daemon/DaemonClient';
import type { ApiSource } from './ApiSource';

export class DaemonApi implements ApiSource {
  readonly kind = 'daemon';

  constructor(private readonly client: DaemonClient) {}

  get(_req: ApiRequest, path: string): Promise<unknown> {
    return this.client.get(path);
  }
}
