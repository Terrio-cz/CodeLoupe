import { ACTION_CH } from '../../shared/actions';
import type { GapReportRefresh } from '../gaps/GapReportRefresh';

export interface ActionContext {
  gapsRefresh: GapReportRefresh;
}

/** Registers the channels of shared/actions.ts; `handle` checks the sender, as for every other channel. */
export function registerActions(ctx: ActionContext, handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void): void {
  handle(ACTION_CH.gapsRefresh, () => ctx.gapsRefresh.run());
}
