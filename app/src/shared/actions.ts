// Actions the page may ask main to do: the page only asks, main validates and does it (confirming first
// in a native dialog where something is changed or removed). Reads go through `api`, never through here.

export const ACTION_CH = {
  gapsRefresh: 'cl:action:gaps-refresh',
} as const;

/** The result of an action in one line the page shows as it is. */
export interface ActionOutcome {
  ok: boolean;
  message: string;
}

export interface ActionsBridge {
  /** Runs `codeloupe metrics gaps` over the transcripts of the last 30 days and stores the report for the Gaps screen. */
  gapsRefresh(): Promise<ActionOutcome>;
}
