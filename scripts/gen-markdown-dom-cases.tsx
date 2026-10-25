// Writes how Chrome lays out the web's chat markdown, so ui/chat's tests can hold the app's HTML
// boxes to it: each input rendered as `MarkdownContent` renders it (react-markdown with the web's
// plugins, raw HTML on or off, and components that build the DOM MdLink, MdImage, MdPre, MdCode and
// MdTable build), put in a `.markdown-content` inside the chat's `text-sm` box in a PPM page open in
// Chrome, and every element's computed style and box read back. Needs that page over DevTools —
// the emulator's Chrome on the scratch server, `adb forward tcp:9335 localabstract:chrome_devtools_remote`:
//
//   PPM_HOME=$(mktemp -d) bun android/scripts/gen-markdown-dom-cases.tsx http://127.0.0.1:9335 <target id>
//
// `--html '<markdown>'` prints one input's DOM instead.
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import remarkBreaks from "remark-breaks";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import { markdownSanitizeSchema, rehypePrefixFragmentLinks, sanitizedRemarkRehypeOptions } from "../../src/web/components/shared/markdown-sanitize";
import { normalizeMathDelimiters } from "../../src/web/lib/markdown-math-delimiters";
import { markdownUrlTransform } from "../../src/web/components/shared/markdown-context";
import { markdownInputs } from "./markdown-cases";

const out = resolve(import.meta.dir, "../ui/chat/src/test/resources/markdown-dom-cases.json");

/** The width of the box the markdown is laid out in, CSS px: wide enough that a short line stays one. */
const WIDTH = 600;

const FILE_EXT_RE = /\.(ts|tsx|js|jsx|mjs|cjs|py|json|md|mdx|yaml|yml|toml|css|scss|less|html|htm|sh|bash|zsh|go|rs|sql|rb|java|kt|swift|c|cpp|h|hpp|cs|vue|svelte|txt|env|cfg|conf|ini|xml|csv|log|dockerfile|makefile|gradle|output)$/i;
const GLOB_CHARS_RE = /[*?{}[\]]/;
const LINE_REF_RE = /^(.+?):(\d+)(?:-(\d+))?$/;
const MERMAID_KEYWORDS = /^(sequenceDiagram|flowchart|graph\s|classDiagram|stateDiagram|erDiagram|journey|gantt|pie|quadrantChart|requirementDiagram|gitGraph|mindmap|timeline|sankey|xychart|block-beta|packet-beta|architecture-beta|kanban)\b/;
const isClickableFilePath = (s: string) => !!s && !s.includes(" ") && !GLOB_CHARS_RE.test(s) && FILE_EXT_RE.test(s);
const basename = (p: string) => p.split(/[\\/]/).filter(Boolean).pop() ?? p;

// The DOM each component builds, with what only reacts to a click or a fetch left out. A wrapper
// a component adds is marked `data-ppm-wrap`, so the measurements can see through it.
function MdLink({ href, children, node, ...props }: any) {
  if (href?.match(/^(https?:)?\/\//i)) return <a href={href} target="_blank" rel="noopener noreferrer" {...props}>{children}</a>;
  return <a href={href} {...props}>{children}</a>;
}
function MdImage({ src, alt, node, ...props }: any) {
  return (
    <img
      src={src || ""}
      alt={alt || (src ? basename(src) : "")}
      data-gallery-item=""
      className="max-h-[400px] max-w-full object-contain rounded-md border border-border cursor-pointer"
      style={{ opacity: 1 }}
      {...props}
    />
  );
}
function hastToText(node: any): string {
  if (!node) return "";
  if (node.type === "text") return node.value ?? "";
  return node.children ? node.children.map(hastToText).join("") : "";
}
function MdPre({ children, node, ...rest }: any) {
  const codeNode = node?.children?.[0];
  const lang = (codeNode?.properties?.className ?? []).find((c: string) => c.startsWith("language-"))?.replace("language-", "");
  const text = hastToText(codeNode);
  if (lang === "mermaid" || (!lang && MERMAID_KEYWORDS.test(text.trim()))) return <pre><code>{text.trim()}</code></pre>;
  return <div className="relative group" data-ppm-wrap=""><pre {...rest}>{children}</pre></div>;
}
function MdCode({ className, children, node, ...rest }: any) {
  if (className) return <code className={className} {...rest}>{children}</code>;
  const text = String(children ?? "").trim();
  const line = text.match(LINE_REF_RE);
  if ((line && isClickableFilePath(line[1]!)) || isClickableFilePath(text)) {
    return <code style={{ cursor: "pointer", textDecoration: "underline", textDecorationStyle: "dotted" }} {...rest}>{children}</code>;
  }
  return <code {...rest}>{children}</code>;
}
function MdTable({ children, node, ...props }: any) {
  return <div className="table-scroll-wrapper overflow-x-auto" data-ppm-wrap=""><table {...props}>{children}</table></div>;
}
const components = { a: MdLink, img: MdImage, pre: MdPre, code: MdCode, table: MdTable };

const remarkPlugins = [[remarkGfm, { singleTilde: false }], [remarkMath, { singleDollarTextMath: false }], remarkBreaks] as any;
const rehypePlugins = [rehypeRaw, [rehypeSanitize, markdownSanitizeSchema], rehypePrefixFragmentLinks] as any;

/** `MarkdownContent`'s cleaning: teammate messages out, runs of blank lines down to one, trimmed. */
function clean(text: string) {
  return text.replace(/<teammate-message[^>]*>[\s\S]*?<\/teammate-message>/g, "").replace(/\n{3,}/g, "\n\n").trim();
}

/** The DOM React builds in the browser: the server renderer's image preload hints are no part of it. */
function render(md: string, rawHtml: boolean) {
  return renderToString(md, rawHtml).replace(/<link rel="preload" as="image"[^>]*>/g, "");
}

function renderToString(md: string, rawHtml: boolean) {
  return renderToStaticMarkup(
    <ReactMarkdown
      remarkPlugins={remarkPlugins}
      rehypePlugins={rawHtml ? rehypePlugins : []}
      remarkRehypeOptions={rawHtml ? sanitizedRemarkRehypeOptions : undefined}
      urlTransform={markdownUrlTransform}
      components={components}
    >
      {normalizeMathDelimiters(clean(md))}
    </ReactMarkdown>,
  );
}

// What these inputs exercise is CSS rather than parsing: margins, white space, alignment, the
// elements only raw HTML brings.
const layoutInputs: string[] = [
  "Soft\nbreak\nparagraph",
  "a  \nb and c\\\nd",
  "> quote\n> soft",
  "1. one\n\n   loose\n   soft\n2. two",
  "- tight\n  soft",
  "<details open>\n<summary>Open one</summary>\n\nShown text\n\n</details>",
  "<details>\n<p>No summary</p>\n</details>",
  "<details open><summary>A</summary><details open><summary>Nested</summary>inner</details></details>",
  "<kbd>Ctrl</kbd> <samp>out</samp> <tt>tt</tt> <var>v</var> <q>quoted <q>nested</q></q>",
  "<b>bold <strong>strong in b</strong></b> <strong>strong <b>b in strong</b></strong>",
  "### Heading <b>with b</b>\n\n# H1 <b>b</b> <strong>s</strong>",
  "<p align=\"right\">Right</p>\n\n<div align=\"right\">Div right</div>\n\n<h2 align=\"center\">Centered h2</h2>",
  "<div align=\"center\">\n<p>Para in center</p>\n</div>",
  "<p align=\"center\">Centered p</p>\n\n<p align=\"justify\">Justified</p>",
  "<p>\nleading newline\n</p>",
  "<p>trailing newline\n</p>\n\n<p>a<br></p>\n\n<p>b<br><br></p>",
  "<div>c<br></div>\n\n<div>d<br><br></div>",
  "<p>  two  spaces  </p>\n\n<div>  collapse   spaces  </div>",
  "<ul>\n<li>one</li>\n<li><p>two</p></li>\n<li>three<ul><li>nested</li></ul></li>\n</ul>",
  "<ol start=\"3\">\n<li>c</li>\n<li value=\"10\">j</li>\n<li>k</li>\n</ol>",
  "<li>stray item</li>",
  "<table>\n<thead><tr><th>H1</th><th>H2</th></tr></thead>\n<tbody><tr><td colspan=\"2\">wide</td></tr><tr><td>a</td></tr></tbody>\n</table>",
  "<table><tr><td><p>para</p><ul><li>x</li></ul></td></tr></table>",
  "<table><tfoot><tr><td>foot</td></tr></tfoot><tbody><tr><td>body</td></tr></tbody></table>",
  "<blockquote>\n<p>raw quote</p>\n</blockquote>",
  "<p>first</p><p>second</p>",
  "<div><p>only</p></div>\n\npara",
  "<section>\n\n# H1 in section\n\n</section>",
  "<ruby>漢<rp>(</rp><rt>kan</rt><rp>)</rp></ruby> after",
  "<sub>sub</sub><sup>sup</sup> in **para**",
  "<abbr title=\"x\">abbr</abbr> <u>u</u> <ins>ins</ins> <s>s</s> <del>del</del> <mark>mark</mark> <small>small <small>smaller</small></small>",
  "<a name=\"anchor\">anchor without href</a> and <a href=\"https://x.dev\">link</a>",
  "<input type=\"checkbox\"> unchecked <input type=\"checkbox\" checked> checked mid-text",
  "<span>a</span>\n\n<div>b</div>",
  "Para one\n\n<div></div>\n\nPara two",
  "<p></p>\n\ntext",
  "- a\n  | x |\n  |---|",
  "- a\n  #### h4",
  "- #### h\n  text",
  "<b>x<div>block in inline</div>y</b>",
  "<a href=\"https://x.dev\"><div>block link</div></a>",
  "<pre>raw <b>bold</b> pre</pre>",
  "<code class=\"language-js\">inline lang code</code> and `src/a.ts:3`",
  "<h4>h4</h4><h5>h5</h5><h6>h6</h6>",
  "<dl><dt>Term</dt><dd><p>Def para</p></dd></dl>",
  "<div>text <p>para</p> more</div>",
  "<p>x<details><summary>in p</summary>body</details>y</p>",
  "<summary>stray summary</summary>",
  "<hr>\n\n<h1>after hr</h1>",
  "- [ ] todo\n- [x] done\n\n1. [ ] ordered task",
  "- [ ] loose\n\n- [x] task",
  "> - a\n>\n> b",
  "<blockquote>\n\n- item\n\n</blockquote>",
  "<div>\n\n```js\nx()\n```\n\n</div>",
  "<ul><li><h2>h2 in item</h2></li><li>b</li></ul>",
  "text\n\n<ul>\n<li>raw after p</li>\n</ul>\n\ntext",
  "<em>em <i>i</i></em> *md em* <i>**bold i**</i>",
  "<p><code>a</code><kbd>b</kbd></p>",
  "x<br>\n<br>\ny",
];

/** Inputs shown with raw HTML off, as a task notification's result is. */
const textInputs: string[] = [
  "<b>x</b> literal",
  "<details>\n<summary>x</summary>\n</details>",
  "text <br> more",
  "<div>\nblock\n</div>\n\nafter",
  "para\n\n<!-- comment -->\n\nmore",
  "- item <i>i</i>\n- <div>x</div>",
];

type Case = { md: string; rawHtml: boolean; html: string };
const cases: Case[] = [
  ...[...markdownInputs, ...layoutInputs].map((md) => ({ md, rawHtml: true, html: render(md, true) })),
  ...textInputs.map((md) => ({ md, rawHtml: false, html: render(md, false) })),
];

const html = process.argv.indexOf("--html");
if (html >= 0) {
  for (const md of process.argv.slice(html + 1)) console.log(JSON.stringify(render(md, true)));
  process.exit(0);
}

/** Runs in the page: each case's elements, in document order, with their boxes and styles. */
function measure(cases: Case[], width: number) {
  const host = document.createElement("div");
  host.className = "text-sm text-text-primary select-text";
  host.style.cssText = `position:absolute;left:0;top:0;width:${width}px;z-index:2147483647`;
  document.body.appendChild(host);
  const px = (v: string) => (v.endsWith("px") ? Math.round(parseFloat(v) * 1000) / 1000 : v);
  const r3 = (v: number) => Math.round(v * 1000) / 1000;
  const results = cases.map((c) => {
    host.innerHTML = `<div class="markdown-content prose-sm">${c.html}</div>`;
    const root = host.firstElementChild as HTMLElement;
    const base = root.getBoundingClientRect();
    const elements: unknown[] = [];
    // Element paths count element children only, a component's wrapper not among them.
    const walk = (parent: Element, path: number[]) => {
      let index = 0;
      const children = (el: Element): Element[] =>
        [...el.children].flatMap((child) => (child.hasAttribute("data-ppm-wrap") ? children(child) : [child]));
      for (const el of children(parent)) {
        const here = [...path, index++];
        const s = getComputedStyle(el);
        const box = el.getBoundingClientRect();
        const wrap = el.parentElement?.hasAttribute("data-ppm-wrap") ? el.parentElement.getBoundingClientRect() : null;
        elements.push({
          path: here.join("/"),
          tag: el.tagName.toLowerCase(),
          box: [r3(box.left - base.left), r3(box.top - base.top), r3(box.width), r3(box.height)],
          ...(wrap ? { wrap: [r3(wrap.left - base.left), r3(wrap.top - base.top), r3(wrap.width), r3(wrap.height)] } : {}),
          style: {
            display: s.display,
            margin: [px(s.marginTop), px(s.marginRight), px(s.marginBottom), px(s.marginLeft)],
            padding: [px(s.paddingTop), px(s.paddingRight), px(s.paddingBottom), px(s.paddingLeft)],
            border: [px(s.borderTopWidth), px(s.borderRightWidth), px(s.borderBottomWidth), px(s.borderLeftWidth)],
            fontSize: px(s.fontSize),
            lineHeight: px(s.lineHeight),
            fontWeight: s.fontWeight,
            fontStyle: s.fontStyle,
            fontFamily: s.fontFamily.split(",")[0].trim().replace(/"/g, ""),
            color: s.color,
            background: s.backgroundColor,
            textAlign: s.textAlign,
            whiteSpace: s.whiteSpace,
            listStyle: `${s.listStyleType} ${s.listStylePosition}`,
            decoration: `${s.textDecorationLine} ${s.textDecorationStyle}`,
            verticalAlign: s.verticalAlign,
            position: s.position,
            top: s.top,
            bottom: s.bottom,
          },
        });
        walk(el, here);
      }
    };
    walk(root, []);
    return { md: c.md, rawHtml: c.rawHtml, html: c.html, height: r3(base.height), elements };
  });
  host.remove();
  return results;
}

const [devtools, target] = process.argv.slice(2);
if (!devtools || !target) {
  console.error("usage: gen-markdown-dom-cases.tsx <devtools base> <target id>");
  process.exit(1);
}
const targets = (await (await fetch(`${devtools}/json`)).json()) as { id: string; webSocketDebuggerUrl: string }[];
const page = targets.find((t) => t.id === target);
if (!page) throw new Error(`no target ${target}`);
await fetch(`${devtools}/json/activate/${target}`);
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolveOpen, reject) => {
  ws.onopen = resolveOpen;
  ws.onerror = reject;
});
const reply = await new Promise<any>((resolveReply) => {
  ws.onmessage = (m) => {
    const data = JSON.parse(String(m.data));
    if (data.id === 1) resolveReply(data);
  };
  ws.send(JSON.stringify({
    id: 1,
    method: "Runtime.evaluate",
    params: { expression: `(${measure.toString()})(${JSON.stringify(cases)}, ${WIDTH})`, returnByValue: true },
  }));
});
ws.close();
const value = reply.result?.result?.value;
if (!Array.isArray(value)) throw new Error(JSON.stringify(reply).slice(0, 2000));
writeFileSync(out, JSON.stringify({ width: WIDTH, cases: value }, null, 1) + "\n");
console.log(`${value.length} cases -> ${out}`);
