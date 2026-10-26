// OPENCODE_ANDROID_TOOL: grep (rootless ripgrep via linker64)
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

function preview(value: string): string {
  let text = value.replace(/\r?\n$/, "")
  if (text.length > 2000) text = text.slice(0, 2000) + "..."
  return text
}

export default tool({
  description:
    "Search file contents by regular expression within the active Location. Use a path to narrow the search, include to filter files by glob, and limit to bound the match count. Returns concise file resources, line numbers, and bounded line previews.",
  args: {
    pattern: tool.schema.string().describe("Regex pattern to search for in file contents"),
    path: tool.schema
      .string()
      .optional()
      .describe("Relative directory to search. Defaults to the active Location."),
    include: tool.schema
      .string()
      .optional()
      .describe('File glob to include in the search (for example, "*.js" or "*.{ts,tsx}")'),
    limit: tool.schema.number().optional().describe("Maximum matches to return"),
  },
  async execute(args, context) {
    const target = path.resolve(context.directory, args.path ?? ".")
    let cwd = target
    let file: string | undefined
    try {
      if (statSync(target).isFile()) {
        cwd = path.dirname(target)
        file = path.basename(target)
      }
    } catch {
      throw new Error(`No existe la ruta ${args.path ?? "."}`)
    }

    const rgArgs = ["--no-config", "--json", "--hidden", "--no-messages"]
    if (args.include) rgArgs.push(`--glob=${args.include}`)
    rgArgs.push("--glob=!**/.git/**", "--", args.pattern, file ?? ".")
    const { stdout, stderr, code } = await runRipgrep(rgArgs, cwd)
    if (code === 2) {
      throw new Error(stderr.trim() || `Patron invalido o error de ripgrep: ${args.pattern}`)
    }
    if (code !== 0 && code !== 1) {
      throw new Error(stderr.trim() || `ripgrep termino con codigo ${code}`)
    }

    const limit = args.limit && args.limit > 0 ? args.limit : Number.MAX_SAFE_INTEGER
    const matches: { file: string; line: number; text: string }[] = []
    for (const raw of stdout.split("\n")) {
      if (!raw) continue
      let record: any
      try {
        record = JSON.parse(raw)
      } catch {
        continue
      }
      if (record?.type !== "match") continue
      const data = record.data
      const found = data?.path?.text
      const line = data?.line_number
      const text = data?.lines?.text
      if (typeof found !== "string" || typeof line !== "number" || typeof text !== "string") {
        continue
      }
      matches.push({ file: path.resolve(cwd, found), line, text: preview(text) })
      if (matches.length >= limit) break
    }

    if (matches.length === 0) return "No files found"
    const out = [`Found ${matches.length} matches`]
    let current = ""
    for (const match of matches) {
      if (match.file !== current) {
        if (current) out.push("")
        current = match.file
        out.push(`${match.file}:`)
      }
      out.push(`  Line ${match.line}: ${match.text}`)
    }
    return out.join("\n")
  },
})
