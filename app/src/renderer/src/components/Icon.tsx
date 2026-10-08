// Inline stroke icons on a 16 px grid, drawn for this app: no icon font, no dependency, colour from currentColor.

const PATHS = {
  overview: 'M2.5 2.5h4.5v5.5H2.5zM9 2.5h4.5v3H9zM9 7.5h4.5v6H9zM2.5 10h4.5v3.5H2.5z',
  branches: 'M4.5 5v6M3 3.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M3 12.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M10 4.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M11.5 6c0 3-7 2-7 5',
  workspaces: 'M2.5 5.5L8 2.5l5.5 3L8 8.5zM2.5 8.5L8 11.5l5.5-3M2.5 11L8 14l5.5-3',
  jobs: 'M2.5 3.5h11v9h-11zM5 6.5l2 1.5-2 1.5M8.5 10h2.5',
  runs: 'M2 8.5h2.5l1.5-4 3 8 1.5-4H14',
  tasks: 'M6.5 4h7M6.5 8h7M6.5 12h7M2.5 4l1 1 1.5-2M2.5 8l1 1 1.5-2M2.8 12h1.4',
  index: 'M2.5 4c0-1.1 2.5-1.8 5.5-1.8s5.5.7 5.5 1.8-2.5 1.8-5.5 1.8S2.5 5.1 2.5 4zM2.5 4v8c0 1.1 2.5 1.8 5.5 1.8s5.5-.7 5.5-1.8V4M2.5 8c0 1.1 2.5 1.8 5.5 1.8s5.5-.7 5.5-1.8',
  gaps: 'M3.5 14V2.5M3.5 3h8l-1.6 2.7 1.6 2.8h-8',
  environment: 'M8 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M8.7 7.3L2.5 13.5M4.5 11.5l1.5 1.5M6 10l1.2 1.2',
  accounts: 'M5.5 5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M2.5 13.5c0-2.8 2.5-4.2 5.5-4.2s5.5 1.4 5.5 4.2',
  settings: 'M2.5 4.5h6M11.5 4.5h2M2.5 11.5h2M7.5 11.5h6M10 3v3M6 10v3',
  refresh: 'M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 2.5v3h-3',
  close: 'M4 4l8 8M12 4l-8 8',
  plus: 'M8 3v10M3 8h10',
  chevron: 'M6 4l4 4-4 4',
  search: 'M7 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM10.6 10.6l3 3',
  external: 'M9.5 2.5h4v4M13.5 2.5l-6 6M11.5 9.5v3a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1h3',
  inbox: 'M2.5 9.5l1.8-6h7.4l1.8 6v3a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1zM2.5 9.5h3l1 1.5h3l1-1.5h3',
  alert: 'M8 2.2l6 10.8H2zM8 6.5v3M8 11.3h.01',
  info: 'M8 14a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM8 7.2V11M8 5h.01',
  check: 'M3.5 8.5l3 3 6-7',
  folder: 'M2.5 4.5a1 1 0 0 1 1-1h3l1.5 1.5h4.5a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z',
  arrowRight: 'M3 8h10M9 4l4 4-4 4',
  arrowLeft: 'M13 8H3M7 4L3 8l4 4',
  loupe: 'M7 11.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM10.3 10.3l3.2 3.2M5 7h4',
  table: 'M2.5 3.5h11v9h-11zM2.5 6.5h11M2.5 9.5h11M6.5 3.5v9',
  chart: 'M2.5 13.5h11M3.5 11l3-4 2.5 2 4-5',
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 16, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg
      className={className ? `icon ${className}` : 'icon'}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
