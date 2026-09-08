import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

describe.skipIf(process.platform !== "win32")("Windows launcher recovery", () => {
  function run(scenario: string, throughLauncher = false) {
    const output = execFileSync("powershell.exe", [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
      resolve("test/launcher-harness.ps1"), "-BridgeScript",
      resolve("start-bridge.ps1"), "-Scenario", scenario,
      ...(throughLauncher ? ["-ThroughLauncher"] : []),
    ], { encoding: "utf8", windowsHide: true, timeout: 10_000 });
    return JSON.parse(output.trim().split(/\r?\n/).at(-1)!);
  }

  it("restarts its unresponsive Bridge even when health times out", () => {
    expect(run("hung")).toEqual({ stopped: true, started: true, failure: null });
  });

  it("does not stop an unrelated process occupying the port", () => {
    expect(run("unrelated")).toMatchObject({ stopped: false, started: false });
  });

  it("recovers the hung Bridge through the double-click launcher", () => {
    expect(run("hung", true)).toEqual({ stopped: true, started: true, failure: null });
  });

  it("does not let Bridge exit races or a busy clipboard terminate the tray manager", () => {
    const bridgeManager = readFileSync(
      fileURLToPath(new URL("../../windows_launcher/BridgeManager.cs", import.meta.url)),
      "utf8",
    );
    const mainWindow = readFileSync(
      fileURLToPath(new URL("../../windows_launcher/MainWindow.xaml.cs", import.meta.url)),
      "utf8",
    );

    expect(bridgeManager).toContain("process.Exited +=");
    expect(bridgeManager).not.toContain("_ownedProcess?.ExitCode");
    expect(bridgeManager).toContain("catch (InvalidOperationException)");
    expect(bridgeManager).toContain("catch (ObjectDisposedException)");
    expect(mainWindow).toContain("TryCopyToClipboard");
  });
});
