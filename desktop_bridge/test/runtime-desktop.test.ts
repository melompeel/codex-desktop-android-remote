import { describe, expect, it, vi } from "vitest";

import { findCodexDesktopAppExecutable } from "../src/runtime/desktop.js";

describe("Codex Desktop runtime", () => {
  it("resolves the Desktop executable declared by the installed package manifest", async () => {
    const desktopExecutable =
      "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.901.6511.0_x64__publisher\\app\\ChatGPT.exe";
    const execute = vi.fn(async (_executable: string, args: string[]) => {
      const command = args.at(-1) ?? "";
      expect(command).toContain("Get-AppxPackageManifest");
      expect(command).toContain("$application.Executable");
      expect(command).not.toContain("'app\\\\Codex.exe'");
      return { stdout: `${desktopExecutable}\r\n` };
    });

    await expect(findCodexDesktopAppExecutable({
      platform: "win32",
      execute,
    })).resolves.toBe(desktopExecutable);
  });
});
