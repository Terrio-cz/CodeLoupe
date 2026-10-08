// First-run onboarding (docs/ui-spec.md § 3.10). The page only asks; main opens the native folder dialog, validates
// what it brought back and has the CodeLoupe CLI write the daemon's configuration. The page never sends a path to write.

export const ONBOARDING_CH = {
  addRepositories: 'cl:onboarding:add-repositories',
  query: 'cl:onboarding:query',
} as const;

export interface RepositoriesAdded {
  ok: boolean;
  /** One line for the user. */
  message: string;
  added: string[];
  already: string[];
  rejected: { path: string; reason: string }[];
}

export interface OnboardingQuery {
  ok: boolean;
  /** What the daemon answered, as text. */
  text: string;
}

export interface OnboardingBridge {
  /** Opens the native folder dialog; 'cancelled' when the user closes it without choosing. */
  addRepositories(): Promise<RepositoriesAdded | 'cancelled'>;
  /** A real query (`outline`, the ranked map of the repository) on one of the daemon's repositories, by its id from the settings. */
  query(repoId: string): Promise<OnboardingQuery>;
}
