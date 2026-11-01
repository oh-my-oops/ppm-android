// Builds the code highlighting the Android app runs, from the repository's own Shiki (a
// dependency of the web app), into one directory that ui/design ships as assets:
//
// - highlighter.js — scripts/highlighter/entry.ts as one IIFE with no imports, which the V8
//   isolate evaluates as-is: Shiki's core, the themes and the web's PRELOAD_LANGS.
// - langs/<name>.json — every other grammar Shiki ships, each once.
// - langs.json — each grammar's name, the grammars it embeds (loaded first), and every alias.
//
// Gradle runs it (ui/design's `buildHighlighter`); by hand:
//
//   bun android/scripts/build-highlighter.ts <out-dir>
import { mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { bundledLanguagesInfo } from "shiki";

const out = process.argv[2];
if (!out) {
  console.error("usage: bun android/scripts/build-highlighter.ts <out-dir>");
  process.exit(2);
}
await rm(out, { recursive: true, force: true });
await mkdir(resolve(out, "langs"), { recursive: true });

const result = await Bun.build({
  entrypoints: [resolve(import.meta.dir, "highlighter/entry.ts")],
  target: "browser",
  // V8 has no DOM: entity decoding must use its worker implementation, not document.createElement.
  conditions: ["worker"],
  format: "iife",
  minify: true,
});
if (!result.success) {
  for (const log of result.logs) console.error(log);
  process.exit(1);
}
await Bun.write(resolve(out, "highlighter.js"), await result.outputs[0]!.text());

type Registration = { name: string; aliases?: string[]; embeddedLangs?: string[] };
const languages: Record<string, { embedded: string[] }> = {};
const aliases: Record<string, string> = {};
for (const info of bundledLanguagesInfo) {
  // A grammar's module lists the grammars it embeds before itself.
  const registrations = (await info.import()).default as unknown as Registration[];
  for (const registration of registrations) {
    if (languages[registration.name]) continue;
    languages[registration.name] = { embedded: registration.embeddedLangs ?? [] };
    for (const alias of registration.aliases ?? []) aliases[alias] = registration.name;
    await Bun.write(resolve(out, "langs", `${registration.name}.json`), JSON.stringify(registration));
  }
  for (const alias of info.aliases ?? []) aliases[alias] ??= info.id;
}
await Bun.write(resolve(out, "langs.json"), JSON.stringify({ languages, aliases }));
console.log(`${out}: highlighter.js ${(Bun.file(resolve(out, "highlighter.js")).size / 1024).toFixed(0)} KiB, ${Object.keys(languages).length} grammars`);
