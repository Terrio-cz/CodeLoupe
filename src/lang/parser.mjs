// Lazy tree-sitter (WASM) init: nothing is loaded until the first parse, so a process that only
// answers from the index never pays for the parser.
import { createRequire } from 'node:module';
import { Parser, Language } from 'web-tree-sitter';

const require = createRequire(import.meta.url);
const GRAMMARS = {
  kotlin: () => require.resolve('@tree-sitter-grammars/tree-sitter-kotlin/tree-sitter-kotlin.wasm'),
};

let init;
const parsers = new Map(); // lang -> Promise<Parser>; the promise is cached so concurrent callers share one load

export function parserFor(lang) {
  if (!GRAMMARS[lang]) return Promise.reject(new Error(`no grammar for ${lang}`));
  if (!parsers.has(lang)) {
    init ??= Parser.init();
    parsers.set(lang, init.then(async () => {
      const p = new Parser();
      p.setLanguage(await Language.load(GRAMMARS[lang]()));
      return p;
    }));
  }
  return parsers.get(lang);
}
