// Tool catalog shared by MCP and the HTTP API/CLI: one definition, one implementation.
import { z } from 'zod';
import * as sym from './query/symbols.mjs';

const root = z.string().optional().describe('Path to the repository or worktree to answer for (absolute). Defaults to the configured defaultRoot.');

export const TOOLS = [
  {
    name: 'find',
    description: 'Find declarations (classes, functions, properties, …) by name, qualified name (Type.member) or glob (*Routes). One line per hit: path:lines [container] signature. Use instead of grep/rg to locate code.',
    shape: {
      root, q: z.string().describe('Name, Type.member, package.Type or glob with * ?'),
      kind: z.enum(['class', 'interface', 'object', 'enum', 'companion', 'annotation', 'fun', 'property', 'constructor', 'enum_entry', 'typealias']).optional(),
      module: z.string().optional().describe('Module path prefix, e.g. "public-api" or "importers/ruian"'),
      test: z.boolean().optional().describe('true = only test sources, false = exclude them'),
      limit: z.number().int().min(1).max(200).optional(),
    },
    run: (view, a) => sym.find(view, a),
  },
  {
    name: 'outline',
    description: 'Members of a file or a type with line ranges and signatures, no bodies. Read this before reading a file; then fetch only the members you need with `symbol`.',
    shape: { root, target: z.string().describe('File path (or unique suffix like "shop/OrderService.kt") or type name (Type, pkg.Type, Outer.Inner)') },
    run: (view, a) => sym.outline(view, a),
  },
  {
    name: 'symbol',
    description: 'Source of one declaration — KDoc, annotations and body — by name: Type.member, member(ParamType, …) for an overload, pkg.Type, or path/File.kt:line. Types over 120 lines return their header and member list unless full=true. Each result carries hash= for later edits.',
    shape: {
      root, name: z.string(), full: z.boolean().optional().describe('Whole body even for large types'),
      all: z.boolean().optional().describe('Return every match instead of listing ambiguous ones'),
    },
    run: (view, a) => sym.symbol(view, a),
  },
];
