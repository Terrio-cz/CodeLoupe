import { useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  numeric?: boolean;
  /** Present when the server (or the caller) can sort by this column. */
  sortKey?: string;
  className?: string;
}

export interface SortState {
  key: string;
  order: 'asc' | 'desc';
}

interface Props<T> {
  label: string;
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  onOpen?: (row: T) => void;
  selected?: string | null;
  sort?: SortState;
  onSort?: (s: SortState) => void;
  empty?: ReactNode;
  /** j/k/Enter while the table has focus (Settings → Klávesové zkratky). */
  shortcuts?: boolean;
}

/** Dense table: sticky header, server-side sorting, keyboard row navigation, row → detail. */
export function DataTable<T>({ label, rows, columns, rowKey, onOpen, selected, sort, onSort, empty, shortcuts = true }: Props<T>) {
  const body = useRef<HTMLTableSectionElement>(null);

  const onKey = (e: KeyboardEvent<HTMLTableRowElement>, row: T) => {
    const tr = e.currentTarget;
    const move = (el: Element | null) => (el as HTMLElement | null)?.focus();
    if (e.key === 'Enter' && onOpen) { e.preventDefault(); onOpen(row); }
    else if (e.key === 'ArrowDown' || (shortcuts && e.key === 'j')) { e.preventDefault(); move(tr.nextElementSibling); }
    else if (e.key === 'ArrowUp' || (shortcuts && e.key === 'k')) { e.preventDefault(); move(tr.previousElementSibling); }
  };

  const header = (c: Column<T>) => {
    const active = sort && c.sortKey && sort.key === c.sortKey;
    const ariaSort = active ? (sort.order === 'asc' ? 'ascending' : 'descending') : c.sortKey && onSort ? 'none' : undefined;
    return (
      <th key={c.key} scope="col" className={c.numeric ? 'num' : undefined} aria-sort={ariaSort}>
        {c.sortKey && onSort ? (
          <button
            className="sort"
            onClick={() => onSort({ key: c.sortKey!, order: active && sort.order === 'desc' ? 'asc' : 'desc' })}
          >
            {c.header}
            <span aria-hidden="true">{active ? (sort.order === 'desc' ? '▼' : '▲') : ''}</span>
          </button>
        ) : (
          c.header
        )}
      </th>
    );
  };

  return (
    <div className="table-wrap">
      <table className="data" aria-label={label}>
        <thead>
          <tr>{columns.map(header)}</tr>
        </thead>
        <tbody ref={body}>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="muted">{empty ?? 'Žádná data.'}</td>
            </tr>
          ) : (
            rows.map(row => {
              const k = rowKey(row);
              return (
                <tr
                  key={k}
                  className={onOpen ? 'clickable' : undefined}
                  tabIndex={onOpen ? 0 : undefined}
                  aria-selected={selected !== undefined ? selected === k : undefined}
                  onClick={onOpen ? () => onOpen(row) : undefined}
                  onKeyDown={onOpen ? e => onKey(e, row) : undefined}
                >
                  {columns.map(c => (
                    <td key={c.key} className={[c.numeric ? 'num' : '', c.className ?? ''].join(' ').trim() || undefined}>
                      {c.render(row)}
                    </td>
                  ))}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
