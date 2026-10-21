// Writes the Edit/MultiEdit preview's rows as the web computes them (`buildRows` in
// src/web/components/chat/edit-diff-compute.ts, on jsdiff) for texts made to give jsdiff a choice
// between equally short diffs, so ui/chat's EditDiffTest can hold the app to the same choices.
// Re-run after the web's diff or jsdiff changes:
//
//   bun android/scripts/gen-edit-diff-cases.ts
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { buildRows } from "../../src/web/components/chat/edit-diff-compute";

const out = resolve(import.meta.dir, "../ui/chat/src/test/resources/edit-diff-cases.json");

// mulberry32: the same cases on every run.
let seed = 51;
const random = () => {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = <T>(items: T[]): T => items[Math.floor(random() * items.length)]!;

const LINES = ["a", "b", "c", "", "  x", "a b", "x = 1;", "}", "\t\tlog(err)"];
const WORDS = ["foo", "bar", " ", "  ", "(", ")", ",", "1", "é", "ứng", "\t", "x", "Ω", "数据", " ", "😀"];

/** A few lines, sometimes ending in a line break, sometimes in CRLF. */
function text(alphabet: string[], joiner: () => string): string {
  const lines = Array.from({ length: Math.floor(random() * 7) }, () => pick(alphabet));
  const crlf = random() < 0.1;
  const body = lines.join(crlf ? "\r\n" : "\n");
  return body + (random() < 0.3 ? (crlf ? "\r\n" : "\n") : "") + joiner();
}

/** [s] with a few lines (or words) removed, added and replaced. */
function mutate(s: string, alphabet: string[], separator: string): string {
  const parts = s.split(separator);
  for (let k = Math.floor(random() * 4); k >= 0; k--) {
    const at = Math.floor(random() * (parts.length + 1));
    const what = random();
    if (what < 0.35) parts.splice(at, 1);
    else if (what < 0.7) parts.splice(at, 0, pick(alphabet));
    else parts.splice(at, 1, pick(alphabet));
  }
  return parts.join(separator);
}

const pairs: Array<[string, string]> = [
  ["", ""],
  ["same\ntext", "same\ntext"],
  ["", "one\ntwo"],
  ["one\n", ""],
  ["a\nb", "a\nb\nc"],
  ["a\nb\n", "a\nb\nc"],
  ["a\r\nb\r\n", "a\r\nB\r\n"],
  ["a\rb", "a\rc"],
  ["function g(a, b) {\n  return a + b;\n}", "function g(a: number, b: number): number {\n  return a + b;\n}"],
  ["let x = 1;", "let x = 2;"],
  ["Ứng dụng quản lý dự án.   ", "Ứng dụng quản lý dự án và quy trình."],
  ["const total = items.length;\nreturn total;", "const total = items.filter(Boolean).length;\nreturn total;"],
  ["import a from \"a\";\n\nfunction f() {\n  return 1;\n}\n", "import a from \"a\";\n\n\nfunction f() {\n\n  return 2;\n}\n"],
  [Array.from({ length: 10 }, (_, i) => `  item${i + 1}: ${i + 1},`).join("\n"), Array.from({ length: 10 }, (_, i) => `  item${i + 1}: ${(i + 1) * 10},`).join("\n")],
  ["数据 库", "数据库 x"],
  ["a  b", "a b"],
  ["x 😀 y", "x 😁 y"],
];
for (let k = 0; k < 250; k++) {
  const old = text(LINES, () => "");
  pairs.push([old, mutate(old, LINES, "\n")]);
}
for (let k = 0; k < 250; k++) {
  const old = Array.from({ length: 1 + Math.floor(random() * 10) }, () => pick(WORDS)).join("");
  const changed = Array.from({ length: 1 + Math.floor(random() * 10) }, () => pick(WORDS)).join("");
  pairs.push([old, random() < 0.5 ? changed : mutate(old, WORDS, " ")]);
}

const cases = pairs.map(([oldStr, newStr]) => {
  const { rows } = buildRows(oldStr, newStr, Number.MAX_SAFE_INTEGER);
  return {
    old: oldStr,
    new: newStr,
    rows: rows.map((row) => ({
      kind: row.kind,
      text: row.text,
      // One run per stretch of highlight: jsdiff can give one changed word as two neighbouring parts.
      ranges: (row.kind === "equal" ? [] : row.ranges).reduce<Array<[number, number]>>((runs, [start, end]) => {
        const last = runs[runs.length - 1];
        if (last && last[1] === start) last[1] = end;
        else runs.push([start, end]);
        return runs;
      }, []),
    })),
    extra: buildRows(oldStr, newStr, 12).extra,
  };
});
writeFileSync(out, JSON.stringify(cases, null, 1) + "\n");
console.log(`${cases.length} cases → ${out}`);
