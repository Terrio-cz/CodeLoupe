// Language adapters: file extension -> grammar + extractor. Adding a language = one entry here.
import { parserFor } from './parser.mjs';
import { extractKotlin } from './kotlin.mjs';

const ADAPTERS = [
  { lang: 'kotlin', ext: /\.kts?$/i, extract: extractKotlin },
];

export const languageOf = path => ADAPTERS.find(a => a.ext.test(path))?.lang ?? null;

export async function extract(path, content) {
  const a = ADAPTERS.find(x => x.ext.test(path));
  if (!a) return null;
  const tree = (await parserFor(a.lang)).parse(content);
  try { return a.extract(tree, content); } finally { tree.delete(); }
}
