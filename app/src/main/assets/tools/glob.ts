// OPENCODE_ANDROID_TOOL: glob (rootless ripgrep via linker64)
import { tool } from "@opencode-ai/plugin"
import { existsSync, statSync } from "node:fs"
import path from "node:path"

function findRipgrep(): string[] {
  const override = process.env.OPENCODE_RG
  if (override && existsSync(override)) {
    return override.endsWith(".so") ? ["/system/bin/linker64", override] : [override]
  }
  const nativeDir = process.env.OPENCODE_NATIVE_LIB_DIR
  const dirs = [
    ...(nativeDir ? [nativeDir] : []),
    ...(process.env.PATH ?? "").split(path.delimiter).filter(Boolean),
  ]
  for (const dir of dirs) {
    const file = path.join(dir, "librg.so")
    if (existsSync(file)) return ["/system/bin/linker64", file]
  }
  const prefix = process.env.PREFIX
  if (prefix) {
    const file = path.join(prefix, "bin", "rg")
    if (existsSync(file)) {
      if (process.env.OPENCODE_RUNTIME === "native" && existsSync("/system/bin/linker64")) {
        return ["/system/bin/linker64", file]
      }
      return [file]
    }
  }
  const which = Bun.which("rg")
  if (which) return [which]
  throw new Error("ripgrep no esta disponible en este runtime")
}

function libraryPath(): string {
  const parts: string[] = []
  const prefix = process.env.PREFIX
  if (prefix) parts.push(path.join(prefix, "lib"))
  if (process.env.OPENCODE_NATIVE_LIB_DIR) parts.push(process.env.OPENCODE_NATIVE_LIB_DIR)
  if (process.env.LD_LIBRARY_PATH) parts.push(process.env.LD_LIBRARY_PATH)
  return [...new Set(parts)].join(path.delimiter)
}

async function runRipgrep(args: string[], cwd: string) {
  const cmd = [...findRipgrep(), ...args]
  const proc = Bun.spawn(cmd, {
    cwd,
    stdin: "ignore",
    stdout: "pipe",
    stderr: "pipe",
    env: { ...process.env, LD_LIBRARY_PATH: libraryPath() },
  })
  const [stdout, stderr, code] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ])
  return { stdout, stderr, code }
}

export default tool({
  description:
    "Find files by glob pattern within the active Location. Returns concise relative file resources. Use a relative path to narrow the search and limit to bound the result count.",
  args: {
    pattern: tool.schema.string().describe("Glob pattern to match files against"),
    path: tool.schema
      .string()
      .optional()
      .describe("Relative directory to search. Defaults to the active Location."),
    limit: tool.schema.number().optional().describe("Maximum results to return"),
  },
  async execute(args, context) {
    const target = path.resolve(context.directory, args.path ?? ".")
    let cwd = target
    try {
      if (statSync(target).isFile()) cwd = path.dirname(target)
    } catch {
      throw new Error(`No existe la ruta ${args.path ?? "."}`)
    }

    const rgArgs = [
      "--no-config",
      "--files",
      `--glob=${args.pattern}`,
      "--glob=!**/.git/**",
      ".",
    ]
    const { stdout, stderr, code } = await runRipgrep(rgArgs, cwd)
    if (code !== 0 && code !== 1) {
      throw new Error(stderr.trim() || `ripgrep termino con codigo ${code}`)
    }

    const limit = args.limit && args.limit > 0 ? args.limit : Number.MAX_SAFE_INTEGER
    const files = stdout
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => path.resolve(cwd, line))
      .slice(0, limit)
    return files.length ? files.join("\n") : "No files found"
  },
})
