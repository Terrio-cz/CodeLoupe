import { describe, expect, it } from 'vitest';
import { concealedFormats, nativeKey, writeConcealed } from '../src/main/env/concealedClipboard';

/** Stands in for Electron's ClipboardItem: keeps the entries the module handed over. */
class FakeItem {
  constructor(readonly entries: Record<string, string | Blob>) {}
}

function fakeClipboard(failWrite = false) {
  const written: FakeItem[][] = [];
  const texts: string[] = [];
  return {
    written,
    texts,
    clipboard: {
      write: async (items: unknown[]) => {
        if (failWrite) throw new Error('refused');
        written.push(items as FakeItem[]);
      },
      writeText: async (text: string) => { texts.push(text); },
    },
  };
}

const bytes = async (blob: string | Blob) => Array.from(new Uint8Array(await (blob as Blob).arrayBuffer()));

describe('concealedFormats', () => {
  it('names the flags of Windows clipboard history, cloud clipboard and monitors', async () => {
    const formats = concealedFormats('win32');
    expect(formats.map(f => f.format)).toEqual(['ExcludeClipboardContentFromMonitorProcessing', 'CanIncludeInClipboardHistory', 'CanUploadToCloudClipboard']);
    expect(formats.map(f => Array.from(f.data))).toEqual([[1, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0]]);
  });

  it('uses the pasteboard type of password managers on macOS and the KDE hint elsewhere', () => {
    expect(concealedFormats('darwin').map(f => f.format)).toEqual(['org.nspasteboard.ConcealedType']);
    expect(concealedFormats('linux').map(f => f.format)).toEqual(['x-kde-passwordManagerHint']);
    expect(new TextDecoder().decode(concealedFormats('linux')[0].data)).toBe('secret');
  });

  it('puts a native format under the key Electron reads it by', () => {
    expect(nativeKey('org.nspasteboard.ConcealedType')).toBe('electron application/osclipboard;format="org.nspasteboard.ConcealedType"');
  });
});

describe('writeConcealed', () => {
  it.each([['win32', 3], ['darwin', 1], ['linux', 1]])('writes the text and the markers of %s in one item', async (platform, markers) => {
    const f = fakeClipboard();
    expect(await writeConcealed(f.clipboard, FakeItem, 'the-value', platform)).toBe('concealed');
    expect(f.written).toHaveLength(1);
    expect(f.written[0]).toHaveLength(1);
    const entries = f.written[0][0].entries;
    expect(entries['text/plain']).toBe('the-value');
    const native = Object.keys(entries).filter(k => k.startsWith('electron application/osclipboard;format='));
    expect(native).toHaveLength(markers);
    expect(native).toEqual(concealedFormats(platform).map(c => nativeKey(c.format)));
    for (const c of concealedFormats(platform)) expect(await bytes(entries[nativeKey(c.format)])).toEqual(Array.from(c.data));
    expect(f.texts).toEqual([]);
  });

  it('never repeats the value inside a marker', async () => {
    const f = fakeClipboard();
    await writeConcealed(f.clipboard, FakeItem, 'the-value', 'darwin');
    const marker = f.written[0][0].entries[nativeKey('org.nspasteboard.ConcealedType')];
    expect(new TextDecoder().decode(new Uint8Array(await bytes(marker)))).not.toContain('the-value');
  });

  it('falls back to plain text when the item cannot be written', async () => {
    const f = fakeClipboard(true);
    expect(await writeConcealed(f.clipboard, FakeItem, 'the-value', 'darwin')).toBe('plain');
    expect(f.texts).toEqual(['the-value']);
  });
});
