// Writes what the web's chat markdown becomes when a reply carries raw HTML: react-markdown's
// remark plugins, remark-rehype with the HTML kept, rehype-raw, rehype-sanitize with
// `markdownSanitizeSchema` and the fragment-link prefix — every step before react-markdown's own
// `urlTransform` and rehype-katex — so ui/chat's MarkdownHtmlTest can hold the app to the same tree.
// Re-run after the pipeline or the inputs change:
//
//   PPM_HOME=$(mktemp -d) bun android/scripts/gen-markdown-html-cases.ts
//
// `--probe '<markdown>'` prints one input's tree instead.
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import remarkBreaks from "remark-breaks";
import remarkRehype from "remark-rehype";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import rehypeStringify from "rehype-stringify";
import { visit } from "unist-util-visit";
import { markdownSanitizeSchema, rehypePrefixFragmentLinks, sanitizedRemarkRehypeOptions } from "../../src/web/components/shared/markdown-sanitize";
import { normalizeMathDelimiters } from "../../src/web/lib/markdown-math-delimiters";
import { markdownInputs } from "./markdown-cases";

const out = resolve(import.meta.dir, "../ui/chat/src/test/resources/markdown-html-cases.json");

const remark = unified()
  .use(remarkParse)
  .use(remarkGfm, { singleTilde: false })
  .use(remarkMath, { singleDollarTextMath: false })
  .use(remarkBreaks);
// react-markdown spreads `allowDangerousHtml: true` over the options it is given.
const rehype = unified()
  .use(remarkRehype, { ...sanitizedRemarkRehypeOptions, allowDangerousHtml: true })
  .use(rehypeRaw)
  .use(rehypeSanitize, markdownSanitizeSchema)
  .use(rehypePrefixFragmentLinks)
  .use(rehypeStringify);

function run(md: string) {
  const mdast = remark.runSync(remark.parse(normalizeMathDelimiters(md)));
  let raw = false;
  visit(mdast, "html", () => {
    raw = true;
  });
  const hast = rehype.runSync(mdast as never);
  return { md, raw, out: rehype.stringify(hast as never) };
}

const probe = process.argv.indexOf("--probe");
if (probe >= 0) {
  for (const md of process.argv.slice(probe + 1)) console.log(JSON.stringify(run(md)));
  process.exit(0);
}


const cases = markdownInputs.map(run);
writeFileSync(out, JSON.stringify(cases, null, 1) + "\n");
console.log(`${cases.length} cases (${cases.filter((c) => c.raw).length} with raw HTML) -> ${out}`);
