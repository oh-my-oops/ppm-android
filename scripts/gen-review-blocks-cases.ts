// Writes what the web makes of many pairs of texts — `computeBlocks` and `lineMap` from
// `src/shared/review-blocks.ts` and each block's `changedSpans` — so core/state's ReviewBlocksTest
// can hold the app to it: a block key the app works out differently is one the server never finds.
// Re-run after any of those changes:
//
//   PPM_HOME=$(mktemp -d) bun android/scripts/gen-review-blocks-cases.ts
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { computeBlocks, lineMap } from "../../src/shared/review-blocks";
import { changedSpans } from "../../src/web/lib/session-review-model";

const out = resolve(import.meta.dir, "../core/state/src/test/resources/review-blocks-cases.json");

const lines = (n: number, prefix = "line") => Array.from({ length: n }, (_, i) => `${prefix} ${i + 1}\n`).join("");

let seed = 0x2545f491;
function rand(): number {
  // xorshift32, deterministic across runs
  seed ^= seed << 13; seed >>>= 0;
  seed ^= seed >>> 17;
  seed ^= seed << 5; seed >>>= 0;
  return seed / 0x100000000;
}
const pick = <T,>(xs: T[]): T => xs[Math.floor(rand() * xs.length)]!;

const vocab = ["a", "b", "c", "x", "}", "", "  return null;", "const v = 1;", "// note", "Tiếng Việt có dấu", "emoji 😀 here", "tab\there", "{", "a"];

function randomText(n: number, crlf: boolean, finalNewline: boolean): string {
  const eol = crlf ? "\r\n" : "\n";
  const ls = Array.from({ length: n }, () => pick(vocab));
  return ls.join(eol) + (n && finalNewline ? eol : "");
}

function mutate(text: string): string {
  const eol = text.includes("\r\n") ? "\r\n" : "\n";
  const hadFinal = text.endsWith("\n");
  const ls = text === "" ? [] : text.replace(/\r?\n$/, "").split(/\r?\n/);
  const edits = 1 + Math.floor(rand() * 6);
  for (let e = 0; e < edits; e++) {
    const at = Math.floor(rand() * (ls.length + 1));
    const kind = rand();
    if (kind < 0.33 && ls.length) ls.splice(Math.min(at, ls.length - 1), 1 + Math.floor(rand() * 3));
    else if (kind < 0.66) ls.splice(at, 0, ...Array.from({ length: 1 + Math.floor(rand() * 4) }, () => pick(vocab)));
    else if (ls.length) ls[Math.min(at, ls.length - 1)] = pick(vocab) + (rand() < 0.5 ? " changed" : "");
  }
  const finalNewline = rand() < 0.85 ? hadFinal : !hadFinal;
  return ls.join(eol) + (ls.length && finalNewline ? eol : "");
}

function edit(text: string, at: number, replacement: string[]): string {
  const all = text.match(/[^\n]*\n|[^\n]+$/g) ?? [];
  all.splice(at, 1, ...replacement.map((l) => `${l}\n`));
  return all.join("");
}

const pairs: { name: string; base: string; current: string }[] = [
  { name: "empty-both", base: "", current: "" },
  { name: "create", base: "", current: "a\nb\n" },
  { name: "delete", base: "a\nb\n", current: "" },
  { name: "same", base: "same\n", current: "same\n" },
  { name: "two-blocks", base: lines(30), current: edit(edit(lines(30), 24, ["changed 25"]), 4, ["changed 5", "added"]) },
  { name: "merge-6", base: lines(30), current: edit(edit(lines(30), 17, ["x"]), 10, ["y"]) },
  { name: "split-7", base: lines(30), current: edit(edit(lines(30), 18, ["x"]), 10, ["y"]) },
  { name: "same-lines", base: lines(40, "same"), current: edit(edit(lines(40, "same"), 30, ["x"]), 5, ["x"]) },
  { name: "crlf-no-final", base: "keep\r\n".repeat(3) + "old 4\r\n" + "keep\r\n".repeat(10) + "old 15\r\nlast", current: ("keep\r\n".repeat(3) + "old 4\r\n" + "keep\r\n".repeat(10) + "old 15\r\nlast").replace("old 4\r\n", "new 4\r\nmore\r\n").replace("old 15\r\nlast", "new 15\r\nlast!") },
  { name: "final-newline-added", base: "a\nb", current: "a\nb\n" },
  { name: "final-newline-removed", base: "a\nb\n", current: "a\nb" },
  { name: "blank-lines", base: "\n\n\n", current: "\n\nx\n\n" },
  { name: "lone-cr", base: "a\rb\nc\n", current: "a\rb\nd\n" },
  { name: "unicode", base: "Xin chào\nemoji 😀\n漢字\n", current: "Xin chào thế giới\nemoji 😀😀\n漢字\n" },
  { name: "word-span", base: "const limit = 5;\n", current: "const limit = max(5);\n" },
  { name: "append-only", base: lines(10), current: lines(14) },
  { name: "prepend", base: lines(10), current: "new top\n" + lines(10) },
  { name: "rewrite", base: lines(20, "old"), current: lines(25, "new") },
  { name: "interleave", base: "a\nb\nc\nd\ne\nf\n", current: "b\na\nd\nc\nf\ne\n" },
  { name: "dollar-word", base: "let $x = a_b;\n", current: "let $y = a_c;\n" },
];
for (let i = 0; i < 160; i++) {
  const crlf = rand() < 0.2;
  const base = randomText(Math.floor(rand() * 40), crlf, rand() < 0.85);
  let current = mutate(base);
  if (rand() < 0.3) current = mutate(current);
  pairs.push({ name: `random-${i}`, base, current });
}
// One long file with scattered edits, so context merging and keys are checked at scale.
{
  let base = lines(2400);
  let current = base;
  for (let i = 0; i < 40; i++) current = edit(current, Math.floor(rand() * 2300), rand() < 0.5 ? ["edited " + i] : ["edited " + i, "and " + i]);
  pairs.push({ name: "long", base, current });
}

const vectors = pairs.map(({ name, base, current }) => {
  const diff = computeBlocks(base, current);
  const map = lineMap(base, current);
  return {
    name,
    base,
    current,
    diff: diff && {
      ...diff,
      blocks: diff.blocks.map((b) => ({ ...b, spans: changedSpans(b.rows) })),
    },
    lineMap: map && Array.from(map),
  };
});
writeFileSync(out, JSON.stringify(vectors));
console.log(`${vectors.length} vectors, ${vectors.reduce((n, v) => n + (v.diff?.blocks.length ?? 0), 0)} blocks`);
