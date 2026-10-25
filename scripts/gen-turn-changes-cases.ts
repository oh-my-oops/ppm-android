// Writes what the web makes of one transcript — each turn's file changes
// (`aggregateTurnFileChanges`), the session's writes (`sessionFileWrites`), its turns
// (`sessionTurns`) and each turn's review against a made-up session list (`editReviews`,
// `turnReviewSummary`, `answerFiles`) — so core/state's TurnChangesTest can hold the app to it.
// Re-run after any of those changes:
//
//   PPM_HOME=$(mktemp -d) bun android/scripts/gen-turn-changes-cases.ts
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { aggregateTurnFileChanges, collectTurnMessages, sessionFileWrites } from "../../src/web/lib/aggregate-turn-file-changes";
import { sessionTurns } from "../../src/web/lib/session-turns";
import { answerFiles, editKey, editReviews, turnReviewSummary } from "../../src/web/lib/turn-review";
import type { ChatMessage } from "../../src/types/chat";
import type { SessionFileChange } from "../../src/shared/session-file-changes";

const out = resolve(import.meta.dir, "../core/state/src/test/resources/turn-changes-cases.json");

const at = (m: number) => `2026-10-25T08:${String(m).padStart(2, "0")}:00.000Z`;
const user = (id: string, content: string, m: number, extra: object = {}) => ({ id, role: "user", content, timestamp: at(m), ...extra });
const assistant = (id: string, events: unknown[], m: number) => ({ id, role: "assistant", content: "", events, timestamp: at(m) });
const use = (tool: string, toolUseId: string, input: object, extra: object = {}) => ({ type: "tool_use", tool, toolUseId, input, ...extra });
const result = (toolUseId: string, output: string) => ({ type: "tool_result", toolUseId, output });
const text = (content: string) => ({ type: "text", content });

const messages = [
  user("u0", "Before the compaction", 0),
  assistant("a0", [use("Edit", "t0", { file_path: "/p/old.ts", old_string: "a", new_string: "b" }), result("t0", "ok")], 1),
  user("c1", "This session is being continued from a previous conversation.", 2, { compaction: { trigger: "auto", preTokens: 1000, postTokens: 100, savedTokens: 900 } }),
  user("u1", "Make the change", 3),
  assistant("a1", [
    text("On it."),
    use("Write", "t1", { file_path: "/p/src/new.ts", content: "export const a = 1;\nexport const b = 2;\n" }),
    result("t1", "File created successfully at: /p/src/new.ts"),
    use("Edit", "t2", { file_path: "/p/src/app.ts", old_string: "foo", new_string: "foo\nbar" }),
    result("t2", "The file /p/src/app.ts has been updated."),
  ], 4),
  assistant("a2", [
    use("MultiEdit", "t3", { file_path: "/p/src/app.ts", edits: [{ old_string: "one\ntwo\nthree\n", new_string: "zero\none\nthree\nfour\n" }, { old_string: "x\ny\n", new_string: "x\ny" }, "junk"] }),
    result("t3", "ok"),
    use("Bash", "t4", { command: "npm test" }),
    result("t4", "passed"),
    use("Agent", "t5", { description: "fix lint", prompt: "Fix the lint" }, {
      children: [
        use("Read", "s1", { file_path: "/p/src/lint.ts" }),
        result("s1", "…"),
        use("Edit", "s2", { file_path: "/p/src/lint.ts", old_string: "var x", new_string: "let x" }),
        result("s2", "ok"),
        use("Agent", "s3", { description: "deeper", prompt: "Go deeper" }, {
          children: [use("Write", "s4", { file_path: "/p/src/app.ts", content: "rewritten\n" }), result("s4", "ok")],
        }),
        result("s3", "done"),
      ],
    }),
    result("t5", "Agent finished"),
    use("NotebookEdit", "t6", { notebook_path: "/p/nb.ipynb", new_source: "print(1)" }),
    text("Done."),
  ], 5),
  user("u2", "[Attached file: /p/notes.md]\n\n", 6),
  assistant("a3", [
    use("Edit", "t7", {
      file_path: "/p/src/a.ts", old_string: "a", new_string: "A",
      files: [
        { file_path: "/p/src/a.ts", op: "update", old_string: "a", new_string: "A" },
        { file_path: "/p/src/b.ts", op: "add", old_string: "", new_string: "b\nb\n" },
        { op: "update" },
      ],
    }),
    use("PowerShell", "t8", { command: "dir" }),
    use("Edit", "t9", { file_path: "", old_string: "a", new_string: "b" }),
    use("Write", "t10", { file_path: "/p/src/c.ts", content: "c" }),
    result("t10", "Overwrote"),
  ], 7),
  user("u3", "<command-name>/review</command-name>\n<command-args>now</command-args>\n\nplease", 8),
  assistant("a4", [text("Nothing to change.")], 9),
] as unknown as ChatMessage[];

const turns: { last: number; changes: ReturnType<typeof aggregateTurnFileChanges> }[] = [];
messages.forEach((msg, i) => {
  if (msg.role !== "assistant" || messages[i + 1]?.role === "assistant") return;
  turns.push({ last: i, changes: aggregateTurnFileChanges(collectTurnMessages(messages, i)) });
});

// The session's list as the server might hold it: blocks naming the calls that wrote them.
const files: SessionFileChange[] = [
  { path: "/p/src/new.ts", status: "added", additions: 2, deletions: 0, baseline: "session", version: "v1", blocks: [{ key: "k1", added: 2, removed: 0, calls: ["t1"] }] },
  {
    path: "/p/src/app.ts", status: "modified", additions: 3, deletions: 2, baseline: "session", version: "v2",
    blocks: [
      { key: "k2", added: 1, removed: 0, kept: true, calls: ["t2"] },
      { key: "k3", added: 2, removed: 1, calls: ["t3", "s4"] },
      { key: "k4", added: 0, removed: 1, kept: true, calls: ["t3"] },
    ],
  },
  { path: "/p/src/lint.ts", status: "modified", additions: 1, deletions: 1, baseline: "head", version: "v3", sinceReview: true },
  { path: "/p/nb.ipynb", status: "modified", baseline: "session", version: "v4", binary: true },
  { path: "/p/src/a.ts", status: "modified", additions: 1, deletions: 1, baseline: "session", version: "v5", blocks: [{ key: "k5", added: 1, removed: 1, calls: ["other"] }] },
];
const reverted = new Map([[editKey("/p/src/c.ts", { toolUseId: "t10", editIndex: 0 }), "undo-1"]]);

const reviews = turns.map(({ changes }) => {
  const map = editReviews(changes, files, reverted);
  return {
    reviews: [...map].map(([key, r]) => ({ key, state: r.state, path: r.file?.path ?? null, keys: r.keys, undoId: r.undoId ?? null })),
    summary: turnReviewSummary(map),
    keepOpen: answerFiles([...map.values()], "open"),
    all: answerFiles([...map.values()], "all"),
  };
});

writeFileSync(out, JSON.stringify({
  messages,
  turns: turns.map((t, i) => ({ ...t, ...reviews[i] })),
  writes: sessionFileWrites(messages),
  sessionTurns: sessionTurns(messages),
  files,
  reverted: [...reverted],
}, null, 1) + "\n");
console.log(`wrote ${out}`);
