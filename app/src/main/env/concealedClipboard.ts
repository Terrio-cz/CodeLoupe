// Copies a revealed secret so that clipboard managers leave it out. Each OS has a marker that password managers set; Electron's
// ClipboardItem writes it next to the text in one commit, as `electron application/osclipboard;format="<native format>"`.
// Plain TypeScript without imports: the clipboard probe of the CI (scripts/clipboard-probe) loads this file in a real Electron.

/** What the platform clipboard is given for a concealed value: the native format name and its payload. */
export interface ConcealedFormat {
  format: string;
  data: Uint8Array<ArrayBuffer>;
}

/** The part of Electron's clipboard module used here. */
export interface WritableClipboard {
  write(items: unknown[]): Promise<void>;
  writeText(text: string): Promise<void>;
}

export type ClipboardItemConstructor = new (entries: Record<string, string | Blob>) => unknown;

const OS_CLIPBOARD = 'electron application/osclipboard';

const dword = (value: number): Uint8Array<ArrayBuffer> => new Uint8Array([value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff, (value >> 24) & 0xff]);
const word = (text: string): Uint8Array<ArrayBuffer> => new TextEncoder().encode(text);

/**
 * Windows: the three flags clipboard history, cloud clipboard and monitors honour (a DWORD each). macOS: the type that clipboard
 * managers and Universal Clipboard skip. Linux (KDE Klipper and others): the password manager hint.
 */
export function concealedFormats(platform: string): ConcealedFormat[] {
  if (platform === 'win32') {
    return [
      { format: 'ExcludeClipboardContentFromMonitorProcessing', data: dword(1) },
      { format: 'CanIncludeInClipboardHistory', data: dword(0) },
      { format: 'CanUploadToCloudClipboard', data: dword(0) },
    ];
  }
  if (platform === 'darwin') return [{ format: 'org.nspasteboard.ConcealedType', data: word('concealed') }];
  return [{ format: 'x-kde-passwordManagerHint', data: word('secret') }];
}

/** The MIME key under which Electron takes a native clipboard format, and the key `clipboard.has` is asked with. */
export const nativeKey = (format: string): string => `${OS_CLIPBOARD};format="${format}"`;

/**
 * Writes [text] with the platform's concealment markers in one commit. When the markers cannot be written (a platform or an Electron
 * version that refuses the item) the text goes out as plain text, so the copy still works; the result says which of the two happened.
 */
export async function writeConcealed(
  clipboard: WritableClipboard,
  ClipboardItem: ClipboardItemConstructor,
  text: string,
  platform: string,
): Promise<'concealed' | 'plain'> {
  try {
    const entries: Record<string, string | Blob> = { 'text/plain': text };
    for (const f of concealedFormats(platform)) entries[nativeKey(f.format)] = new Blob([f.data]);
    await clipboard.write([new ClipboardItem(entries)]);
    return 'concealed';
  } catch {
    await clipboard.writeText(text);
    return 'plain';
  }
}
