// PreToolUse: blocks edits to generated or machine-specific files.
let raw = "";
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  const target = String(input?.tool_input?.file_path ?? "").replace(/\\/g, "/");
  const rules = [
    [/\/app\/schemas\//, "los esquemas de Room los genera KSP al compilar; no se editan a mano"],
    [/\/local\.properties$/, "local.properties es especifico de esta maquina"],
    [/\/gradle\/wrapper\//, "el wrapper de Gradle no se toca sin una tarea explicita"],
    [/\/gradlew(\.bat)?$/, "los scripts del wrapper no se tocan"],
    [/\/(build|\.gradle|\.kotlin)\//, "es un directorio generado"],
  ];
  for (const [pattern, reason] of rules) {
    if (pattern.test(target)) {
      console.error(`Bloqueado por .claude/hooks/protect-files.mjs: ${reason}. Archivo: ${target}`);
      process.exit(2);
    }
  }
  process.exit(0);
});
