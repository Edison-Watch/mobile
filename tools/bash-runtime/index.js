import { Bash, InMemoryFs, defineCommand } from "just-bash/browser";

const SAFE_COMMANDS = [
  "alias", "awk", "base64", "basename", "bash", "cat", "chmod", "clear",
  "column", "comm", "cp", "cut", "date", "diff", "dirname", "du", "echo",
  "env", "expand", "export", "expr", "false", "file", "find", "fold", "grep",
  "head", "help", "history", "hostname", "join", "jq", "ln", "ls", "md5sum",
  "mkdir", "mv", "nl", "od", "paste", "printenv", "printf", "pwd", "readlink",
  "rev", "rg", "rm", "rmdir", "sed", "seq", "sh", "sha1sum", "sha256sum",
  "sort", "split", "stat", "strings", "tac", "tail", "tee", "time", "touch",
  "tr", "tree", "true", "unalias", "unexpand", "uniq", "wc", "which", "whoami",
  "xan", "xargs", "yq",
];

const FILE_SYSTEM_LIMIT = 32 * 1024 * 1024;
const PER_FILE_LIMIT = 8 * 1024 * 1024;
const MAX_SLEEP_MILLIS = 30_000;

function byteLength(value) {
  if (typeof value === "string") return new TextEncoder().encode(value).byteLength;
  if (value instanceof Uint8Array) return value.byteLength;
  return new TextEncoder().encode(String(value)).byteLength;
}

function cappedFileSystem() {
  const fs = new InMemoryFs({}, { maxTotalBytes: FILE_SYSTEM_LIMIT });
  return new Proxy(fs, {
    get(target, property) {
      if (property === "writeFile" || property === "writeFileSync") {
        return (path, content, ...rest) => {
          if (byteLength(content) > PER_FILE_LIMIT) {
            throw new Error(`EFBIG: virtual file exceeds ${PER_FILE_LIMIT} bytes, '${path}'`);
          }
          return target[property](path, content, ...rest);
        };
      }
      if (property === "appendFile") {
        return async (path, content, ...rest) => {
          let current = 0;
          try { current = (await target.stat(path)).size; } catch { /* new file */ }
          if (current + byteLength(content) > PER_FILE_LIMIT) {
            throw new Error(`EFBIG: virtual file exceeds ${PER_FILE_LIMIT} bytes, '${path}'`);
          }
          return target.appendFile(path, content, ...rest);
        };
      }
      if (property === "writeFileLazy") {
        return (path, provider, ...rest) => target.writeFileLazy(path, async () => {
          const content = await provider();
          if (byteLength(content) > PER_FILE_LIMIT) {
            throw new Error(`EFBIG: virtual file exceeds ${PER_FILE_LIMIT} bytes, '${path}'`);
          }
          return content;
        }, ...rest);
      }
      const value = target[property];
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

function hostCommand(namespace) {
  return defineCommand(namespace, async (args) => {
    try {
      const encoded = globalThis.__mobileCommand(JSON.stringify({ namespace, args }));
      const result = JSON.parse(encoded);
      if (result.supplementToken) globalThis.__mobileSupplements.push(result.supplementToken);
      delete result.supplementToken;
      return result;
    } catch (error) {
      return {
        stdout: "",
        stderr: `${namespace}: ${error instanceof Error ? error.message : String(error)}\n`,
        exitCode: 1,
      };
    }
  });
}

function sleepCommand() {
  return defineCommand("sleep", async (args) => {
    if (args.length === 0) {
      return { stdout: "", stderr: "sleep: missing operand\n", exitCode: 1 };
    }

    let totalMillis = 0;
    for (const operand of args) {
      const match = /^(?:\d+(?:\.\d*)?|\.\d+)([smhd]?)$/.exec(operand);
      if (!match) {
        return {
          stdout: "",
          stderr: `sleep: invalid time interval '${operand}'\n`,
          exitCode: 1,
        };
      }

      const multiplier = { "": 1_000, s: 1_000, m: 60_000, h: 3_600_000, d: 86_400_000 }[match[1]];
      const millis = Number.parseFloat(operand) * multiplier;
      if (!Number.isFinite(millis)) {
        return {
          stdout: "",
          stderr: `sleep: invalid time interval '${operand}'\n`,
          exitCode: 1,
        };
      }
      totalMillis += millis;
    }

    if (totalMillis > MAX_SLEEP_MILLIS) {
      return {
        stdout: "",
        stderr: "sleep: total delay exceeds the Mobile Bash limit of 30 seconds\n",
        exitCode: 1,
      };
    }

    await globalThis.__mobileSleep(Math.round(totalMillis));
    return { stdout: "", stderr: "", exitCode: 0 };
  });
}

function blockedCommand(name) {
  return defineCommand(name, async () => ({
    stdout: "",
    stderr: `${name}: blocked by Mobile Bash policy\n`,
    exitCode: 126,
  }));
}

const BLOCKED_COMMANDS = [
  "curl", "wget", "python", "python3", "node", "js-exec", "sqlite3",
  "tar", "gzip", "gunzip", "zcat",
];

globalThis.__mobileBash = new Bash({
  fs: cappedFileSystem(),
  cwd: "/home/agent",
  env: {
    HOME: "/home/agent",
    USER: "agent",
    LOGNAME: "agent",
    HOSTNAME: "android",
    TZ: "UTC",
    PATH: "/usr/bin:/bin",
  },
  commands: SAFE_COMMANDS,
  customCommands: [
    ...globalThis.__mobileHostNamespaces.map(hostCommand),
    ...BLOCKED_COMMANDS.map(blockedCommand),
    sleepCommand(),
  ],
  executionLimitProfile: "hardened",
  executionLimits: {
    maxSourceBytes: 64 * 1024,
    maxExecDepth: 32,
    maxCallDepth: 50,
    maxCommandCount: 10_000,
    maxLoopIterations: 10_000,
    maxAwkIterations: 10_000,
    maxSedIterations: 10_000,
    maxJqIterations: 10_000,
    maxWorkUnits: 250_000,
    maxTraversalEntries: 10_000,
    maxTraversalDepth: 128,
    maxTraversalWork: 50_000,
    maxLiveBytes: 32 * 1024 * 1024,
    maxInputBytes: 8 * 1024 * 1024,
    maxFileSystemBytes: FILE_SYSTEM_LIMIT,
    maxExecutionTimeMs: 60_000,
    maxStringLength: 8 * 1024 * 1024,
    maxArrayElements: 100_000,
    maxHeredocSize: 1024 * 1024,
    maxOutputSize: 1024 * 1024,
    maxFileDescriptors: 256,
  },
});

globalThis.__mobileBashExec = async script => {
  globalThis.__mobileSupplements = [];
  const result = await globalThis.__mobileBash.exec(script);
  return JSON.stringify({
    stdout: result.stdout,
    stderr: result.stderr,
    exitCode: result.exitCode,
    supplementTokens: globalThis.__mobileSupplements,
  });
};
