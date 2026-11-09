/**
 * Records what a real server sends, for the generated decoders' tests
 * (`core/protocol/src/test/resources/protocol-samples.json`).
 *
 * Runs against the scratch server (`android/scripts/scratch-server.sh start`), on a `mock` chat
 * session so no AI is paid for. Re-run after a change to what the server sends:
 *
 *   bun android/scripts/capture-protocol-samples.ts
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const env = Object.fromEntries(
  readFileSync("/tmp/ppm-android-scratch.env", "utf8").split("\n").filter(Boolean).map((l) => l.split("=", 2) as [string, string]),
);
const base = env.URL!;
const token = env.PASSWORD!;
const project = env.PROJECT!;
const OUT = resolve(import.meta.dir, "../core/protocol/src/test/resources/protocol-samples.json");

async function api(method: string, path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return res.json();
}

/** Every frame a socket sends until [until] says stop or [ms] pass. */
function record(path: string, send: (ws: WebSocket, frame: Record<string, unknown>) => void, until: (f: Record<string, unknown>) => boolean, ms: number): Promise<unknown[]> {
  return new Promise((done) => {
    const frames: unknown[] = [];
    const sep = path.includes("?") ? "&" : "?";
    const ws = new WebSocket(`${base.replace(/^http/, "ws")}${path}${sep}token=${encodeURIComponent(token)}`);
    const finish = () => { clearTimeout(timer); ws.close(); done(frames); };
    const timer = setTimeout(finish, ms);
    ws.onmessage = (e) => {
      if (typeof e.data !== "string") return;
      let frame: Record<string, unknown>;
      try { frame = JSON.parse(e.data); } catch { return; }
      frames.push(frame);
      send(ws, frame);
      if (until(frame)) finish();
    };
  });
}

const info = await api("GET", "/api/info");
const projects = await api("GET", "/api/projects");
const created = (await api("POST", `/api/project/${project}/chat/sessions`, { providerId: "mock", title: "samples" })) as { data: { id: string } };
const sessionId = created.data.id;

// The global socket first, so it hears the chat turn's phase and unread broadcasts.
const global = record("/ws/global", (ws, f) => {
  if (f.type === "global_ready") ws.send(JSON.stringify({ type: "watch", projectName: project }));
}, () => false, 12_000);

let sent = false;
const chat = await record(`/ws/project/${project}/chat/${sessionId}?providerId=mock`, (ws, f) => {
  if (!sent && f.type === "session_state") {
    sent = true;
    ws.send(JSON.stringify({ type: "message", content: "hello" }));
  }
}, (f) => f.type === "done", 10_000);

const lsp = await record(`/ws/project/${project}/lsp`, () => {}, () => false, 1_500);
const globalFrames = await global;

const sessions = await api("GET", `/api/project/${project}/chat/sessions?limit=20`);
const history = await api("GET", `/api/project/${project}/chat/sessions/${sessionId}/messages?providerId=mock&limit=50`);
await api("DELETE", `/api/project/${project}/chat/sessions/${sessionId}?providerId=mock`);

const samples = { info, projects, sessions, history, chat, global: globalFrames, lsp };
writeFileSync(OUT, JSON.stringify(samples, null, 2) + "\n");
console.log(`chat ${chat.length}, global ${globalFrames.length}, lsp ${lsp.length} frames → ${OUT}`);
