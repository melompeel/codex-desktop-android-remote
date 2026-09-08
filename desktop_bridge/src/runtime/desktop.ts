import { execFile } from "node:child_process";
import { readdir, stat } from "node:fs/promises";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export async function detectDesktopPackageVersion(): Promise<string | null> {
  if (process.env.CODEX_DESKTOP_VERSION) return process.env.CODEX_DESKTOP_VERSION;
  if (process.platform !== "win32") return null;
  try {
    const { stdout } = await execFileAsync(
      "powershell.exe",
      [
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "(Get-AppxPackage -Name OpenAI.Codex).Version.ToString()",
      ],
      { windowsHide: true },
    );
    return stdout.trim() || null;
  } catch {
    return null;
  }
}

export async function findCodexDesktopAppExecutable(): Promise<string | null> {
  if (process.env.CODEX_DESKTOP_PATH) return process.env.CODEX_DESKTOP_PATH;
  if (process.platform !== "win32") return null;
  try {
    const { stdout } = await execFileAsync(
      "powershell.exe",
      [
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "$package = Get-AppxPackage -Name OpenAI.Codex | " +
          "Sort-Object Version -Descending | Select-Object -First 1; " +
          "if ($package) { $executable = Join-Path $package.InstallLocation 'app\\Codex.exe'; " +
          "if (Test-Path -LiteralPath $executable) { $executable } }",
      ],
      { windowsHide: true, timeout: 5_000 },
    );
    return stdout.trim() || null;
  } catch {
    return null;
  }
}

export async function findCodexExecutable(): Promise<string> {
  return (await findCodexRuntime()).executable;
}

export type CodexRuntime = {
  executable: string;
  version: string | null;
};

export async function findCodexRuntime(
  expectedVersion?: string,
): Promise<CodexRuntime> {
  if (process.env.CODEX_CLI_PATH) {
    return {
      executable: process.env.CODEX_CLI_PATH,
      version:
        process.env.CODEX_CLI_VERSION ??
        await detectCodexCliVersion(process.env.CODEX_CLI_PATH),
    };
  }
  const localAppData = process.env.LOCALAPPDATA;
  if (localAppData) {
    const root = join(localAppData, "OpenAI", "Codex", "bin");
    try {
      const entries = await readdir(root, { withFileTypes: true });
      const paths = [
        join(root, "codex.exe"),
        ...entries
          .filter((entry) => entry.isDirectory())
          .map((entry) => join(root, entry.name, "codex.exe")),
      ];
      const candidates = await Promise.all(paths.map(async (executable) => {
        try {
          const [file, version] = await Promise.all([
            stat(executable),
            detectCodexCliVersion(executable),
          ]);
          return { executable, version, modified: file.mtimeMs };
        } catch {
          return null;
        }
      }));
      const available = candidates
        .filter((candidate): candidate is NonNullable<typeof candidate> => candidate !== null)
        .sort((left, right) => right.modified - left.modified);
      const exact = expectedVersion
        ? available.find((candidate) => candidate.version === expectedVersion)
        : null;
      const selected = exact ?? available[0];
      if (selected) {
        return {
          executable: selected.executable,
          version: selected.version,
        };
      }
    } catch {}
  }
  return {
    executable: "codex",
    version: process.env.CODEX_CLI_VERSION ?? await detectCodexCliVersion("codex"),
  };
}

export async function detectCodexCliVersion(
  executable: string,
): Promise<string | null> {
  try {
    const { stdout } = await execFileAsync(executable, ["--version"], {
      windowsHide: true,
      timeout: 5_000,
    });
    const match = stdout.trim().match(/^codex-cli\s+(.+)$/);
    return match?.[1]?.trim() || null;
  } catch {
    return null;
  }
}
