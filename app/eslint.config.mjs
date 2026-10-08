import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['out/**', 'dist/**', 'stage/**', 'node_modules/**'] },
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ['src/main/**/*.ts', 'src/preload/**/*.ts', 'src/shared/**/*.ts', '*.ts', 'test/**/*.ts', 'test/**/*.mjs', 'scripts/**/*.mjs'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['src/renderer/**/*.{ts,tsx}', 'test/**/*.tsx'],
    languageOptions: { globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    // Only the classic hook rules; the React Compiler checks in the recommended set flag deliberate patterns here.
    rules: { "react-hooks/rules-of-hooks": "error", "react-hooks/exhaustive-deps": "off" },
  },
);
