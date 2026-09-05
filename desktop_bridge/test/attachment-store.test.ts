import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  AttachmentStore,
  MAX_ATTACHMENT_BYTES,
} from "../src/attachments/store.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(
    roots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

describe("AttachmentStore", () => {
  it("stores a verified image under an opaque path and resolves it for its owner", async () => {
    const root = await temporaryRoot();
    const store = new AttachmentStore(root, () => 1_000, () => "attachment-1");
    const image = png();

    const saved = await store.save("device-1", "screen.png", "image/png", image);

    expect(saved).toEqual({
      attachmentId: "attachment-1",
      name: "screen.png",
      mimeType: "image/png",
      size: image.length,
      kind: "image",
      expiresAt: 3_601_000,
    });
    const [resolved] = await store.resolve("device-1", ["attachment-1"]);
    expect(resolved).toMatchObject({
      label: "screen.png",
      kind: "image",
    });
    expect(resolved?.path).toBe(join(root, "attachment-1", "screen.png"));
    expect(resolved?.fsPath).toBe(resolved?.path);
  });

  it("accepts a validated local text file for mention input", async () => {
    const root = await temporaryRoot();
    const store = new AttachmentStore(root, () => 1_000, () => "file-1");
    const saved = await store.save(
      "device-1",
      "sample.kt",
      "text/plain; charset=utf-8",
      Buffer.from("fun main() = println(\"hello\")\n", "utf8"),
    );

    expect(saved.kind).toBe("file");
    await expect(store.resolve("device-1", ["file-1"])).resolves.toMatchObject([
      {
        attachmentId: "file-1",
        label: "sample.kt",
        kind: "file",
        path: join(root, "file-1", "sample.kt"),
      },
    ]);
  });

  it("rejects traversal, unsupported files, MIME mismatches, and oversized data", async () => {
    const store = new AttachmentStore(await temporaryRoot());

    await expect(store.save("device", "../screen.png", "image/png", png())).rejects.toThrow(
      "invalid-attachment-name",
    );
    await expect(store.save("device", "CON.txt", "text/plain", Buffer.from("hello"))).rejects.toThrow(
      "invalid-attachment-name",
    );
    await expect(store.save("device", "program.exe", "application/octet-stream", Buffer.from("MZ"))).rejects.toThrow(
      "unsupported-attachment-media-type",
    );
    await expect(store.save("device", "screen.jpg", "image/png", png())).rejects.toThrow(
      "attachment-type-mismatch",
    );
    await expect(store.save("device", "screen.png", "image/png", Buffer.from("not png"))).rejects.toThrow(
      "attachment-content-mismatch",
    );
    await expect(
      store.save(
        "device",
        "screen.png",
        "image/png",
        Buffer.alloc(MAX_ATTACHMENT_BYTES + 1),
      ),
    ).rejects.toThrow("attachment-too-large");
  });

  it("isolates devices and removes expired files", async () => {
    let now = 1_000;
    const store = new AttachmentStore(
      await temporaryRoot(),
      () => now,
      () => "attachment-2",
      500,
    );
    await store.save("device-1", "screen.png", "image/png", png());

    await expect(store.resolve("device-2", ["attachment-2"])).rejects.toThrow(
      "attachment-not-found",
    );
    now = 1_501;
    await expect(store.resolve("device-1", ["attachment-2"])).rejects.toThrow(
      "attachment-not-found",
    );
  });

  it("restores live metadata after restart and prunes it after the TTL", async () => {
    let now = 1_000;
    const root = await temporaryRoot();
    const first = new AttachmentStore(root, () => now, () => "persisted-1", 500);
    await first.save("device-1", "notes.md", "text/markdown", Buffer.from("# Notes\n"));

    const restored = await AttachmentStore.open(root, {
      now: () => now,
      ttlMs: 500,
    });
    await expect(restored.resolve("device-1", ["persisted-1"])).resolves.toMatchObject([
      { attachmentId: "persisted-1", label: "notes.md", kind: "file" },
    ]);

    now = 1_501;
    const expired = await AttachmentStore.open(root, { now: () => now, ttlMs: 500 });
    await expect(expired.resolve("device-1", ["persisted-1"])).rejects.toThrow(
      "attachment-not-found",
    );
  });
});

async function temporaryRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "codex-remote-attachments-"));
  roots.push(root);
  return root;
}

function png(): Buffer {
  return Buffer.from([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x00, 0x00, 0x00, 0x00,
  ]);
}
