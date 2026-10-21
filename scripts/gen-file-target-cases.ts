// Writes what the web makes of texts a chat's markdown may hold — a link's destination
// (`parseMarkdownFileTarget`, and `MdLink` on the `href` micromark's `normalizeUri` builds of it),
// an inline code (`MdCode` in markdown-code-block.tsx) and the `:120` / `#L120` suffix
// (`splitSourceLocation`) — so ui/chat's FileTargetsTest can hold the app to the same answers.
// Re-run after any of those change:
//
//   bun android/scripts/gen-file-target-cases.ts
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { normalizeUri } from "micromark-util-sanitize-uri";
import { defaultUrlTransform } from "react-markdown";
import { FILE_EXT_RE, GLOB_CHARS_RE, parseMarkdownFileTarget } from "../../src/web/components/shared/markdown-context";
import { splitSourceLocation } from "../../src/web/lib/source-location";

const out = resolve(import.meta.dir, "../ui/chat/src/test/resources/file-target-cases.json");

// MdCode's own two rules, which markdown-code-block.tsx keeps to itself.
const LINE_REF_RE = /^(.+?):(\d+)(?:-(\d+))?$/;
const isClickableFilePath = (s: string) => !!s && !s.includes(" ") && !GLOB_CHARS_RE.test(s) && FILE_EXT_RE.test(s);
function inlineCode(code: string) {
  const text = code.trim();
  const m = text.match(LINE_REF_RE);
  if (m && isClickableFilePath(m[1]!)) return { path: m[1]!, line: { start: Number(m[2]), end: m[3] ? Number(m[3]) : undefined } };
  return isClickableFilePath(text) ? { path: text, line: undefined } : null;
}

let seed = 52;
const random = () => {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = <T>(items: T[]): T => items[Math.floor(random() * items.length)]!;

const texts = [
  // The web's own tests.
  "src/app.ts:120", "src/app.ts:120:5", "src/app.ts:120-140", "src/app.ts#L120", "src/app.ts#L120-L140", "src/app.ts#L120-140",
  "D:/Projects/nxsys/src/PaymentForm.tsx:160", "C:/repo/app.ts", "src/app.ts", "", "README", "app:tsx",
  "app.ts:0", "app.ts#L0", "app.ts:10-9", "app.ts:9007199254740992", "app.ts#L1-L9007199254740992", "app.ts:9007199254740991",
  "C:/Users/PC/project/src/app.ts", "C:\\Users\\PC\\project\\src\\app.ts", "/Users/alex/project/src/app.ts", "./src/app.ts",
  "../shared/app.ts", "Dockerfile", "Makefile", ".gitignore", "schema.custom-extension", "src/components", "src/components/",
  "docs", "docs/", "~/project/README.md", "src/app/[slug]/page.tsx", "/C:/Users/PC/app.ts",
  "file:///C:/Users/PC/My%20Project/app.ts", "file:///home/alex/app.ts", "file://localhost/home/alex/app.ts",
  "file://localhost/C:/Users/PC/app.ts", "./t%C3%A0i%20li%E1%BB%87u/%5Bslug%5D.md", "/C:/My%20Project/src/%5Bslug%5D.ts#L12-L18",
  "docs/guide.md#installation", "#installation", "https://example.com/app.ts", "http://example.com/app.ts:10",
  "//example.com/app.ts", "mailto:person@example.com", "javascript:alert(1)", "data:text/plain,hello.ts", "vscode://file/C:/app.ts",
  "custom:app.ts", "file://server/share/app.ts", "src/*.ts", "src/app?.ts", "app.ts?raw=1", "bad%ZZ.ts", "bad%.ts", "bad%C3.ts",
  "src/%00app.ts", "src/%0Aapp.ts", "src/\u0000app.ts", "src/\napp.ts", "src/\rapp.ts", "src/\tapp.ts",
  // Inline code.
  "package.json", "bun test", "  src/a.ts:12  ", "src/a.ts:0", "src/a.ts:20-10", "src/a.TS", "a.ts:12\n", "\u00a0src/a.ts\ufeff",
  "src/app.ts:120:5", "src/{a,b}.ts", "x.output", "Makefile.makefile", "notes.MD:3-4", "a b.ts", "C:/a.ts:7",
  "%E0%A4%A", "%ED%A0%80.ts", "%C0%AF.ts", "%F4%90%80%80.ts", "%F0%9F%98%80.ts", "%e2%82%ac.md", "a+b%20c.ts",
  "/\\server\\share.ts", "\\\\server\\share.ts", "\\/x.ts", "a.ts\u2028:12", "src/app.ts:١٢",
  // Link destinations, before `normalizeUri`.
  "my file.ts", "tài liệu.md", "bad%.ts", "100%.md", "a%2", "a\"b<c>.ts", "a|b^c`d.ts", "x\ud800.ts", "x\udc00y.md", "😀.md",
  "https://example.com/a b", "HTTPS://x.dev", "//cdn.dev/a.ts", "mailto:a@b.c", "MailTo:a@b.c", "irc://x", "xmpp:a@b", "tel:123",
  "ftp://x/a.ts", "javascript:alert(1)", "./a.ts?x=1", "/abs/path?x=1", "a:b/c", "a/b:c", "#top", "?q=1",
];
const parts = ["src", "/", "\\", "a", ".ts", ".md", ":", "12", "0", "-", "#L", "L", "%", "%20", "%C3%A0", "%ZZ", "?", "*", " ",
  "\n", "C:", "~", "file://", "localhost", "#", ".", "x", "é", "[", "]", "{", "}", "\u2028", "\t", "00", "9007199254740993"];
for (let k = 0; k < 400; k++) {
  texts.push(Array.from({ length: 1 + Math.floor(random() * 7) }, () => pick(parts)).join(""));
}

const plain = (v: unknown) => (v === undefined ? null : v);
const target = (t: { path: string; line?: { start: number; end?: number } } | null) =>
  t ? { path: t.path, line: t.line ? { start: t.line.start, end: plain(t.line.end) } : null } : null;
// `MdLink` on a destination: a web address opens as one; a file or folder opens in PPM; anything
// else keeps the `href` `markdownUrlTransform` leaves it, which the app opens only when it has a
// scheme (a relative one would resolve against the page).
const mdLink = (destination: string) => {
  const href = normalizeUri(destination);
  const file = /^(https?:)?\/\//i.test(href) ? null : parseMarkdownFileTarget(href);
  const kept = defaultUrlTransform(href);
  return { href, file: target(file), url: !file && kept && /^[a-z][a-z\d+.-]*:/i.test(kept) ? kept : null };
};
const cases = texts.map((text) => ({
  text,
  split: target(splitSourceLocation(text)),
  link: target(parseMarkdownFileTarget(text)),
  code: target(inlineCode(text)),
  mdLink: mdLink(text),
}));
writeFileSync(out, JSON.stringify(cases, null, 1) + "\n");
console.log(`${cases.length} cases → ${out}`);
