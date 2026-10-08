import type { ReactNode } from 'react';
import type { Route, Screen } from './router';
import { Branches } from './screens/Branches';
import { Environment } from './screens/Environment';
import { Gaps } from './screens/Gaps';
import { IndexScreen } from './screens/IndexScreen';
import { Jobs } from './screens/Jobs';
import { Overview } from './screens/Overview';
import { Runs } from './screens/Runs';
import { Settings } from './screens/Settings';
import { Tasks } from './screens/Tasks';
import { Workspaces } from './screens/Workspaces';

/** The component of each screen; a screen in screenList.ts without an entry here does not compile. */
export const VIEWS: Record<Screen, (route: Route) => ReactNode> = {
  overview: () => <Overview />,
  branches: route => <Branches route={route} />,
  workspaces: route => <Workspaces route={route} />,
  tasks: route => <Tasks route={route} />,
  jobs: route => <Jobs route={route} />,
  runs: route => <Runs route={route} />,
  index: () => <IndexScreen />,
  gaps: () => <Gaps />,
  environment: route => <Environment route={route} />,
  settings: () => <Settings />,
};
