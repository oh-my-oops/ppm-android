// The chat's code highlighting, as one script for the V8 isolate of androidx.javascriptengine.
//
// The web highlights fenced code with Shiki (src/web/theme/adapters/shiki-adapter.ts): the same
// Shiki, themes and grammars here colour a code block exactly as the browser does. Two
// differences, both forced by the sandbox. The JavaScript regex engine instead of Oniguruma (no
// WebAssembly loader there), pinned to ES2024: Shiki's default output mis-matches on V8 once its
// regexes tier up — SQL's `MAX(` lost its colour against the web in a 51-case comparison, which
// ES2024 got 51/51 right — and forgiving, so a pattern it cannot translate leaves that one rule
// uncoloured instead of the whole block. And the grammars past the web's PRELOAD_LANGS arrive as
// JSON (`ppmLoadLanguage`), the first time a block asks for one, as the web loads them on first use.
// Built by `bun android/scripts/build-highlighter.ts`; `node android/scripts/check-highlighter.mjs`
// compares what it colours with the web, block by block.
import { createHighlighterCoreSync } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import type { LanguageRegistration } from "shiki/core";

import githubDarkDimmed from "@shikijs/themes/github-dark-dimmed";
import githubLight from "@shikijs/themes/github-light";
import oneDarkPro from "@shikijs/themes/one-dark-pro";

// The web's PRELOAD_LANGS (`bash` is shellscript's alias).
import javascript from "@shikijs/langs/javascript";
import typescript from "@shikijs/langs/typescript";
import tsx from "@shikijs/langs/tsx";
import jsx from "@shikijs/langs/jsx";
import json from "@shikijs/langs/json";
import shellscript from "@shikijs/langs/shellscript";
import python from "@shikijs/langs/python";
import go from "@shikijs/langs/go";
import rust from "@shikijs/langs/rust";
import sql from "@shikijs/langs/sql";
import yaml from "@shikijs/langs/yaml";
import markdown from "@shikijs/langs/markdown";
import html from "@shikijs/langs/html";
import css from "@shikijs/langs/css";
import docker from "@shikijs/langs/docker";

// ES2024 output needs the `v` flag (V8 11.2+); ES2018 is the closest without it.
const vFlag = (() => {
  try {
    new RegExp("", "v");
    return true;
  } catch {
    return false;
  }
})();

const highlighter = createHighlighterCoreSync({
  themes: [githubDarkDimmed, githubLight, oneDarkPro],
  langs: [javascript, typescript, tsx, jsx, json, shellscript, python, go, rust, sql, yaml, markdown, html, css, docker],
  engine: createJavaScriptRegexEngine({ target: vFlag ? "ES2024" : "ES2018", forgiving: true }),
});

const g = globalThis as Record<string, unknown>;

/**
 * Lines of `[text, colour, fontStyle]` runs plus the theme's own foreground and background, as
 * JSON. [lang] is a loaded grammar's name or "text". fontStyle is Shiki's bit set: 1 italic,
 * 2 bold, 4 underline, 8 strikethrough.
 */
g.ppmHighlight = (code: string, lang: string, theme: string): string => {
  // No per-line time limit. This engine compiles a grammar's patterns as they are first used, and
  // for a big grammar that alone outruns Shiki's 500 ms: emacs-lisp's first line took 800 ms on
  // desktop V8, and the rest of it came out in one colour. The isolate is off the UI thread.
  const result = highlighter.codeToTokens(code, { lang: lang as never, theme: theme as never, tokenizeTimeLimit: 0 });
  return JSON.stringify({
    fg: result.fg,
    bg: result.bg,
    lines: result.tokens.map((line) => line.map((t) => [t.content, t.color ?? null, t.fontStyle ?? 0])),
  });
};

/** One grammar of `langs/<name>.json`, written into the call as it is (JSON is a JS literal), after the ones it embeds. */
g.ppmLoadLanguage = (grammar: LanguageRegistration): void => {
  highlighter.loadLanguageSync(grammar);
};

g.ppmLanguages = (): string => JSON.stringify(highlighter.getLoadedLanguages());
