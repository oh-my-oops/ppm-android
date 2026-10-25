// Builds the file icons the Android app draws, from the web's own copy of the vscode-icons theme,
// into one directory that ui/design ships as assets:
//
// - file-icons/tables.json — the name tables of src/web/lib/file-icons.generated.ts, which
//   `FileIcons` resolves names by;
// - file-icons/dark/<name>.svg — every glyph from src/web/styles/file-icons.generated.css, as the
//   dark workbench draws it, and file-icons/light/<name>.svg — the few the theme draws again for a
//   light one. One file each, so a list reads only the glyphs it shows.
//
// Gradle runs it (ui/design's `buildFileIcons`); by hand:
//
//   bun android/scripts/build-file-icons.ts <out-dir>
import { mkdir, readFile, rm } from "node:fs/promises";
import { resolve } from "node:path";
import * as tables from "../../src/web/lib/file-icons.generated";

const out = process.argv[2];
if (!out) {
  console.error("usage: bun android/scripts/build-file-icons.ts <out-dir>");
  process.exit(2);
}
await rm(out, { recursive: true, force: true });
await mkdir(resolve(out, "file-icons", "dark"), { recursive: true });
await mkdir(resolve(out, "file-icons", "light"), { recursive: true });

const css = await readFile(resolve(import.meta.dir, "../../src/web/styles/file-icons.generated.css"), "utf8");
const dark: Record<string, string> = {};
const light: Record<string, string> = {};
const rule = /(:root\.light )?\.vsi-([\w-]+) \{\s*background-image: url\("data:image\/svg\+xml,(.*?)"\);\s*\}/g;
for (const m of css.matchAll(rule)) {
  (m[1] ? light : dark)[m[2]!] = decodeURIComponent(m[3]!);
}

const missing = tables.ICON_NAMES.filter((name) => !dark[name]);
if (missing.length > 0) {
  console.error(`no drawing for ${missing.length} icons: ${missing.slice(0, 5).join(", ")}…`);
  process.exit(1);
}

await Bun.write(
  resolve(out, "file-icons", "tables.json"),
  JSON.stringify({
    extensions: tables.EXTENSION_ICONS,
    filenames: tables.FILENAME_ICONS,
    frameworkExtensions: tables.FRAMEWORK_EXTENSION_ICONS,
    frameworkFilenames: tables.FRAMEWORK_FILENAME_ICONS,
    folders: tables.FOLDER_ICONS,
    foldersOpen: tables.FOLDER_OPEN_ICONS,
    dbEngines: tables.DB_ENGINE_ICONS,
    defaultFile: tables.DEFAULT_FILE_ICON,
    defaultFolder: tables.DEFAULT_FOLDER_ICON,
    defaultFolderOpen: tables.DEFAULT_FOLDER_OPEN_ICON,
    maxExtensionSegments: tables.MAX_EXTENSION_SEGMENTS,
    light: Object.keys(light),
  }),
);
for (const [name, svg] of Object.entries(dark)) await Bun.write(resolve(out, "file-icons", "dark", `${name}.svg`), svg);
for (const [name, svg] of Object.entries(light)) await Bun.write(resolve(out, "file-icons", "light", `${name}.svg`), svg);
console.log(`${Object.keys(dark).length} icons, ${Object.keys(light).length} light drawings`);
