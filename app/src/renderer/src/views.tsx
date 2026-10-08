import type { ReactNode } from 'react';
import type { Route, Screen } from './router';
import { Branches } from './screens/Branches';
import { Environment } from './screens/Environment';
import { Gaps } from './screens/Gaps';
import { IndexScreen } from './screens/IndexScreen';
import { Overview } from './screens/Overview';
import { Settings } from './screens/Settings';
import { Tasks } from './screens/Tasks';

/** The component of each screen; a screen in screenList.ts without an entry here does not compile. */
export const VIEWS: Record<Screen, (route: Route) => ReactNode> = {
  overview: () => <Overview />,
  branches: route => <Branches route={route} />,
  tasks: route => <Tasks route={route} />,
  index: () => <IndexScreen />,
  gaps: () => <Gaps />,
  environment: route => <Environment route={route} />,
  settings: () => <Settings />,
};
