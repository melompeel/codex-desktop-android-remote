import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { FileDeviceRegistry } from "../src/security/device-registry.js";

const temporaryDirectories: string[] = [];

describe("file device registry", () => {
  afterEach(async () => {
    await Promise.all(
      temporaryDirectories.splice(0).map((directory) =>
        rm(directory, { recursive: true, force: true }),
      ),
    );
  });

  it("persists a credential before issue resolves and reloads it", async () => {
    const directory = await mkdtemp(join(tmpdir(), "codex-remote-registry-"));
    temporaryDirectories.push(directory);
    const filePath = join(directory, "devices.json");
    const registry = await FileDeviceRegistry.open(filePath);

    const credential = await registry.issue("android-1", "Pixel", "android");
    const persisted = JSON.parse(await readFile(filePath, "utf8")) as Array<{
      deviceId: string;
      tokenHash: string;
    }>;

    expect(persisted).toHaveLength(1);
    expect(persisted[0]?.deviceId).toBe("android-1");
    expect(persisted[0]?.tokenHash).not.toBe(credential.token);

    const reopened = await FileDeviceRegistry.open(filePath);
    expect(reopened.findByToken(credential.token)?.deviceId).toBe("android-1");

    await reopened.revoke("android-1");
    const afterRevoke = await FileDeviceRegistry.open(filePath);
    expect(afterRevoke.findByToken(credential.token)).toBeNull();
  });
});
