// PostToolUse: after editing a file under domain/, runs only its matching unit test class.
import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

let raw = "";
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  const file = String(input?.tool_input?.file_path ?? "").replace(/\\/g, "/");
  if (!/\/app\/src\/(main|test)\/java\/.+\/domain\/.+\.kt$/.test(file)) process.exit(0);

  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  const base = path.basename(file, ".kt").replace(/Test$/, "");
  const testDir = path.join(root, "app", "src", "test");
  if (!hasTestFor(testDir, `${base}Test.kt`)) process.exit(0);

  const javaHome = resolveJavaHome();
  if (!javaHome) process.exit(0);

  const gradle = `"${path.join(root, process.platform === "win32" ? "gradlew.bat" : "gradlew")}"`;
  const result = spawnSync(gradle, ["testDebugUnitTest", "--tests", `*${base}Test`, "-q"], {
    cwd: root,
    shell: true,
    encoding: "utf8",
    timeout: 280000,
    env: { ...process.env, JAVA_HOME: javaHome },
  });
  if (result.status !== 0) {
    const out = `${result.stdout ?? ""}${result.stderr ?? ""}`.split("\n").slice(-40).join("\n");
    console.error(`Fallaron los tests de ${base}Test tras editar ${file}:\n${out}`);
    process.exit(2);
  }
  process.exit(0);
});

// Uses JAVA_HOME if valid, else Android Studio's bundled JBR. Null => hook skips instead of blocking edits.
function resolveJavaHome() {
  const candidates = [
    process.env.JAVA_HOME,
    "C:\\Program Files\\Android\\Android Studio\\jbr",
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
  ];
  for (const dir of candidates) {
    if (dir && (existsSync(path.join(dir, "bin", "java.exe")) || existsSync(path.join(dir, "bin", "java")))) {
      return dir;
    }
  }
  return null;
}

function hasTestFor(dir, fileName) {
  if (!existsSync(dir)) return false;
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (hasTestFor(full, fileName)) return true;
    } else if (entry === fileName) {
      return true;
    }
  }
  return false;
}
