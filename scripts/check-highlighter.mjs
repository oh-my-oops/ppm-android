// Checks the code highlighting the app ships against the web's: `highlighter.js` and the grammars,
// driven as SyntaxHighlighter.kt drives them (the web's aliases, then Shiki's; a grammar after the
// ones it embeds), in a bare V8 context like the sandbox's isolate — against the web's own Shiki
// (Oniguruma) — character by character, for every sample in every built-in theme. Run it on node:
// bun is JavaScriptCore, and the regex engine's output is V8's question.
//
//   bun android/scripts/build-highlighter.ts /tmp/shiki && node android/scripts/check-highlighter.mjs /tmp/shiki
import fs from "node:fs";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const repo = fileURLToPath(new URL("../..", import.meta.url));
const assets = process.argv[2];
if (!assets) {
  console.error("usage: node android/scripts/check-highlighter.mjs <dir build-highlighter.ts wrote>");
  process.exit(2);
}
const { createHighlighter } = await import(`${repo}node_modules/shiki/dist/index.mjs`);

// One block per language: the web's preloaded ones, then grammars loaded on demand.
const samples = [
  ["ts", `// Greets\nexport async function hello(name: string): Promise<number> {\n  const n = await fetch(\`/api/\${name}\`, { method: "POST" });\n  return n.status ?? 42;\n}\ninterface A<T> { a?: T[]; readonly b: Map<string, number> }\n`],
  ["tsx", `import { useState } from "react";\nexport function App({ title }: { title: string }) {\n  const [n, setN] = useState(0);\n  return <div className="p-2" onClick={() => setN(n + 1)}>{title} {n}</div>;\n}\n`],
  ["js", `const re = /a+b?\\d{2,}/gi;\nclass Foo extends Bar { #x = 1; static y() { return this.#x; } }\nconsole.log(\`x \${1 + 2}\`, null, undefined, true);\n`],
  ["jsx", `const el = <Button variant="primary" disabled={false}>Save</Button>;\n`],
  ["json", `{\n  "name": "ppm",\n  "version": "0.23.1",\n  "private": true,\n  "deps": [1, 2.5, null]\n}\n`],
  ["bash", `#!/usr/bin/env bash\nset -euo pipefail\nfor f in "$@"; do\n  echo "file: \${f%.ts}" | grep -E '^[a-z]+' > /dev/null && rm -f "$f"\ndone\nexport PATH="$HOME/bin:$PATH"\n`],
  ["sh", `ls -la ~ | wc -l # count\n`],
  ["zsh", `typeset -A map; map[key]=value; print -r -- \${(k)map}\n`],
  ["console", `$ bun install\nbun install v1.3.11\n+ shiki@4.3.1\n`],
  ["python", `import asyncio\n\n@dataclass\nclass Point:\n    x: int = 0\n    def dist(self, other: "Point") -> float:\n        """Docstring."""\n        return ((self.x - other.x) ** 2) ** 0.5  # comment\nprint(f"{Point(1).x!r:>10}")\n`],
  ["go", `package main\n\nimport "fmt"\n\nfunc main() {\n\tch := make(chan int, 3)\n\tgo func() { ch <- 42 }()\n\tfmt.Printf("%d\\n", <-ch)\n}\n`],
  ["rust", `#[derive(Debug, Clone)]\nstruct P<'a> { name: &'a str, n: u32 }\nfn main() -> Result<(), Box<dyn std::error::Error>> {\n    let v: Vec<_> = (0..10).map(|x| x * 2).collect();\n    println!("{:?}", v);\n    Ok(())\n}\n`],
  ["sql", `SELECT u.id, COUNT(*) AS n, MAX(o.total)\nFROM users u LEFT JOIN orders o ON o.user_id = u.id\nWHERE u.created_at > '2026-01-01' AND u.flag IS NOT NULL\nGROUP BY u.id HAVING COUNT(*) > 3 ORDER BY n DESC LIMIT 10;\n`],
  ["yaml", `name: CI\non:\n  push:\n    branches: [main]\njobs:\n  build:\n    runs-on: ubuntu-latest # runner\n    steps:\n      - uses: actions/checkout@v4\n      - run: bun test --timeout 5000\n`],
  ["md", `# Title\n\nSome *em* and **strong** text with \`code\` and a [link](https://x.y).\n\n- item\n- item 2\n\n\`\`\`ts\nconst a = 1;\n\`\`\`\n`],
  ["html", `<!doctype html>\n<html lang="en">\n<head><style>body { color: red; }</style></head>\n<body><script>let x = 1 + 2;</script><p class="a">Hi &amp; bye</p></body>\n</html>\n`],
  ["css", `@media (max-width: 767px) {\n  .a > .b:hover::after { content: "x"; color: #fff !important; margin: 0 calc(1rem + 2px); }\n}\n:root { --x: 12px; }\n`],
  ["dockerfile", `FROM oven/bun:1.3 AS build\nWORKDIR /app\nCOPY package.json bun.lock ./\nRUN bun install --frozen-lockfile\nENV NODE_ENV=production\nCMD ["bun", "src/index.ts"]\n`],
  ["kotlin", `package a.b\n\n@Composable\nfun Hello(name: String, n: Int = 0): Unit {\n    val s = "Hi \${name.uppercase()} $n"\n    when (n) { 0 -> println(s); else -> TODO() }\n}\ndata class P(val x: Double?)\n`],
  ["java", `public final class Main {\n  @Override\n  public static void main(String[] args) throws Exception {\n    var list = new ArrayList<String>(); // comment\n    System.out.println("n=" + list.size() + 'c');\n  }\n}\n`],
  ["swift", `import SwiftUI\nstruct ContentView: View {\n    @State private var n = 0\n    var body: some View { Text("Count \\(n)").padding(8) }\n}\n`],
  ["cpp", `#include <vector>\n#define MAX(a, b) ((a) > (b) ? (a) : (b))\ntemplate <typename T>\nclass Box { public: explicit Box(T v) : v_(std::move(v)) {} private: T v_; };\nint main() { std::vector<int> v{1, 2}; return MAX(v[0], 3); }\n`],
  ["c", `#include <stdio.h>\nstatic int f(const char *s) { return s ? (int)strlen(s) : -1; }\nint main(void) { printf("%d\\n", f("hi")); return 0; }\n`],
  ["csharp", `using System;\nnamespace App {\n  public record Point(int X, int Y);\n  class P { static async Task Main() { var p = new Point(1, 2); Console.WriteLine($"{p.X}"); await Task.Delay(1); } }\n}\n`],
  ["ruby", `class Greeter\n  attr_reader :name\n  def initialize(name) = @name = name\n  def greet = puts "Hello, #{name}!" # hi\nend\n%w[a b].each { |x| p x.to_sym }\n`],
  ["php", `<?php\nnamespace App;\nfunction f(array $xs): int { return count($xs) + PHP_INT_MAX; }\necho "x {$y} " . f([1, 2]);\n`],
  ["lua", `local M = {}\nfunction M.add(a, b) return a + b end -- add\nfor i = 1, 10 do print(("%d"):format(i)) end\nreturn M\n`],
  ["toml", `[package]\nname = "ppm"\nversion = "0.1.0" # v\n[dependencies]\nserde = { version = "1", features = ["derive"] }\n`],
  ["xml", `<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n  <!-- comment -->\n  <application android:label="@string/app" />\n</manifest>\n`],
  ["diff", `diff --git a/x.ts b/x.ts\n--- a/x.ts\n+++ b/x.ts\n@@ -1,3 +1,3 @@\n-const a = 1;\n+const a = 2;\n context\n`],
  ["ini", `[core]\n  editor = vim\n; comment\nname=value\n`],
  ["makefile", `.PHONY: build\nbuild: deps\n\t@echo "building $(TARGET)"\n\t$(CC) -o $@ $^\n`],
  ["vue", `<template>\n  <div :class="{ a: x }" @click="n++">{{ n }}</div>\n</template>\n<script setup lang="ts">\nimport { ref } from "vue";\nconst n = ref(0);\n</script>\n<style scoped>.a { color: red; }</style>\n`],
  ["svelte", `<script>\n  let n = 0;\n</script>\n<button on:click={() => n++}>{n}</button>\n<style>button { padding: 4px; }</style>\n`],
  ["graphql", `query Q($id: ID!) {\n  user(id: $id) { name ...F @include(if: true) }\n}\nfragment F on User { email }\n`],
  ["scala", `object Main extends App {\n  case class P(x: Int)\n  val xs = List(1, 2).map(_ * 2)\n  println(s"xs=$xs")\n}\n`],
  ["haskell", `module Main where\nimport Data.List (sort)\nmain :: IO ()\nmain = print . sort $ [3, 1, 2] -- sorted\n`],
  ["elixir", `defmodule M do\n  @moduledoc "Doc"\n  def f(%{a: a} = m), do: {:ok, a, m}\nend\n`],
  ["zig", `const std = @import("std");\npub fn main() !void {\n    const x: u32 = 42;\n    std.debug.print("{d}\\n", .{x});\n}\n`],
  ["dart", `void main() async {\n  final xs = <int>[1, 2];\n  print('n: \${xs.length}');\n}\n`],
  ["r", `library(dplyr)\nx <- c(1, 2, 3) %>% mean() # mean\nf <- function(a = TRUE) if (a) "y" else NULL\n`],
  ["perl", `use strict;\nmy @xs = (1, 2);\nprint "n=$#xs\\n" if $xs[0] =~ /^\\d+$/;\n`],
  ["powershell", `$ErrorActionPreference = 'Stop'\nGet-ChildItem -Path $env:TEMP | Where-Object { $_.Length -gt 1MB } | ForEach-Object { Write-Host $_.Name }\n`],
  ["nginx", `server {\n  listen 443 ssl;\n  location /api/ { proxy_pass http://127.0.0.1:8080; }\n}\n`],
  ["hcl", `resource "aws_s3_bucket" "b" {\n  bucket = "my-\${var.env}"\n  tags   = { Name = "x" }\n}\n`],
  ["prisma", `model User {\n  id    Int    @id @default(autoincrement())\n  email String @unique\n}\n`],
  ["objective-c", `@interface Foo : NSObject\n@property (nonatomic) NSInteger n;\n@end\n`],
  ["latex", `\\documentclass{article}\n\\begin{document}\nHello $x^2 + \\alpha$ \\textbf{bold}\n\\end{document}\n`],
  ["clojure", `(defn f [x] (-> x inc (* 2))) ; doc\n(println (f 1) :kw "s")\n`],
  ["erlang", `-module(m).\n-export([f/1]).\nf(X) when is_integer(X) -> {ok, X + 1}.\n`],
  ["nix", `{ pkgs ? import <nixpkgs> {} }:\npkgs.mkShell { buildInputs = [ pkgs.bun ]; }\n`],
  ["ocaml", `let rec fact n = if n <= 1 then 1 else n * fact (n - 1)\nlet () = Printf.printf "%d\\n" (fact 5)\n`],
  ["asm", `section .text\nglobal _start\n_start:\n  mov rax, 60 ; exit\n  syscall\n`],
  ["emacs-lisp", `(defun hello (name)\n  "Say hello."\n  (message "Hello, %s" name))\n`],
  ["wolfram", `f[x_] := x^2 + Sin[x]\nPlot[f[x], {x, 0, 2 Pi}]\n`],
  ["ansi", `\u001b[31mred\u001b[0m and \u001b[1;32mbold green\u001b[0m\n`],
  ["text", `plain text, no grammar\n`],
  ["", `no language at all\n`],
  ["foo-unknown", `unknown language\n`],
];

const themes = ["github-dark-dimmed", "github-light", "one-dark-pro"];

// The web: src/web/theme/adapters/shiki-adapter.ts.
const PRELOAD = ["javascript", "typescript", "tsx", "jsx", "json", "shellscript", "bash", "python", "go", "rust", "sql", "yaml", "markdown", "html", "css", "docker"];
const LANG_ALIASES = { js: "javascript", ts: "typescript", sh: "bash", shell: "bash", zsh: "bash", py: "python", rs: "rust", yml: "yaml", md: "markdown", dockerfile: "docker", golang: "go" };
const web = await createHighlighter({ themes, langs: PRELOAD });
const webLoaded = new Set(PRELOAD);
async function webTokens(code, lang, theme) {
  const l = (lang || "").toLowerCase().trim();
  let resolved = LANG_ALIASES[l] ?? l;
  if (resolved && !webLoaded.has(resolved)) {
    try {
      await web.loadLanguage(resolved);
      webLoaded.add(resolved);
    } catch {
      resolved = "text";
    }
  }
  const { tokens, fg, bg } = web.codeToTokens(code, { lang: resolved || "text", theme });
  return { fg, bg, lines: tokens.map((line) => line.map((t) => [t.content, t.color, t.fontStyle ?? 0])) };
}

// The app: android/ui/design/.../SyntaxHighlighter.kt.
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(`${assets}/highlighter.js`, "utf8"), context);
const index = JSON.parse(fs.readFileSync(`${assets}/langs.json`, "utf8"));
const loaded = new Set(JSON.parse(vm.runInContext("ppmLanguages()", context)));
const SPECIAL = new Set(["text", "plaintext", "txt", "plain", "ansi"]);
function resolve(lang) {
  const l = (lang || "").trim().toLowerCase();
  const name = LANG_ALIASES[l] ?? l;
  if (!name) return "text";
  if (SPECIAL.has(name)) return name;
  const id = index.aliases[name] ?? name;
  return id in index.languages ? id : "text";
}
function load(name) {
  const embedded = index.languages[name]?.embedded;
  if (loaded.has(name) || !embedded) return;
  for (const e of embedded) load(e);
  vm.runInContext(`ppmLoadLanguage(${fs.readFileSync(`${assets}/langs/${name}.json`, "utf8")})`, context);
  loaded.add(name);
}
function appTokens(code, lang, theme) {
  const name = resolve(lang);
  load(name);
  return JSON.parse(vm.runInContext(`ppmHighlight(${JSON.stringify(code)},${JSON.stringify(name)},${JSON.stringify(theme)})`, context));
}

const hex = (c) => c?.toUpperCase().replace(/^#(.)(.)(.)$/, "#$1$1$2$2$3$3").replace(/^#(.{6})FF$/, "#$1");
// Colour and font style per character; a space's colour is never seen.
function characters({ lines, fg }) {
  const out = [];
  lines.forEach((line, i) => {
    if (i > 0) out.push(["\n"]);
    for (const [text, color, style] of line) for (const ch of text) out.push(/\s/.test(ch) ? [ch] : [ch, hex(color ?? fg), Math.max(style, 0)]);
  });
  return out;
}

let failed = 0;
for (const theme of themes) {
  for (const [lang, code] of samples) {
    const label = `${theme} ${lang || "(none)"}`;
    let app;
    try {
      app = appTokens(code, lang, theme);
    } catch (e) {
      console.log(`FAIL ${label}: ${e.message}`);
      failed++;
      continue;
    }
    const w = await webTokens(code, lang, theme);
    const [expected, actual] = [characters(w), characters(app)];
    const diffs = [];
    if (hex(w.fg) !== hex(app.fg) || hex(w.bg) !== hex(app.bg)) diffs.push(`theme ${w.fg}/${w.bg}, app ${app.fg}/${app.bg}`);
    if (expected.length !== actual.length) diffs.push(`${expected.length} characters, app ${actual.length}`);
    expected.forEach((e, i) => {
      if (e.join() !== actual[i]?.join()) diffs.push(`${JSON.stringify(e[0])} at ${i}: ${e.slice(1)}, app ${actual[i]?.slice(1)}`);
    });
    if (diffs.length) {
      failed++;
      console.log(`DIFF ${label}: ${diffs.slice(0, 4).join("; ")}${diffs.length > 4 ? ` (+${diffs.length - 4})` : ""}`);
    }
  }
}
const total = themes.length * samples.length;
console.log(`${total - failed}/${total} blocks coloured as on the web`);
process.exit(failed ? 1 : 0);
