import { describe, expect, it } from "vitest";

import { BridgeStore, hashQueue } from "../src/domain/store.js";

describe("BridgeStore", () => {
  it("applies revisioned snapshots and patches without guessing across gaps", () => {
    const store = new BridgeStore();
    store.applyStreamChange("thread-1", {
      type: "snapshot",
      revision: 7,
      conversationState: { title: "Demo", requests: [] },
    });
    store.applyStreamChange("thread-1", {
      type: "patches",
      baseRevision: 7,
      revision: 8,
      patches: [{ op: "replace", path: ["title"], value: "Updated" }],
    });

    expect(store.getThread("thread-1")).toMatchObject({
      revision: 8,
      state: { title: "Updated" },
    });
    expect(() =>
      store.applyStreamChange("thread-1", {
        type: "patches",
        baseRevision: 6,
        revision: 9,
        patches: [],
      }),
    ).toThrow("stream-revision-gap");
  });

  it("allows only the first non-expired response for an approval", () => {
    let now = 1_000;
    const store = new BridgeStore(() => now);
    store.registerApproval({
      requestId: "approval-1",
      threadId: "thread-1",
      method: "item/commandExecution/requestApproval",
      expiresAt: 2_000,
      payload: {},
    });

    expect(store.claimApproval("approval-1", "android-request").requestId).toBe(
      "approval-1",
    );
    expect(() => store.claimApproval("approval-1", "ones-request")).toThrow(
      "approval-already-resolved",
    );

    store.registerApproval({
      requestId: "approval-2",
      threadId: "thread-1",
      method: "item/fileChange/requestApproval",
      expiresAt: 1_500,
      payload: {},
    });
    now = 1_501;
    expect(() => store.claimApproval("approval-2", "late-request")).toThrow(
      "approval-expired",
    );
  });

  it("mirrors the complete owner queue with a stable conflict hash", () => {
    const store = new BridgeStore();
    const first = store.applyQueueSnapshot("thread-1", [
      { id: "queued-1", text: "第一条", createdAt: 20, context: { prompt: "第一条" } },
      { id: "queued-2", text: "第二条", createdAt: 30 },
    ]);

    expect(first.hash).toHaveLength(64);
    expect(store.assertQueueHash("thread-1", first.hash).messages).toHaveLength(2);
    expect(
      store.queueWithoutMessage("thread-1", "queued-1", first.hash),
    ).toEqual([{ id: "queued-2", text: "第二条", createdAt: 30 }]);
    expect(() =>
      store.queueWithoutMessage("thread-1", "queued-1", "stale-hash"),
    ).toThrow("queue-hash-conflict");
    expect(() => store.assertQueueHash("missing", first.hash)).toThrow(
      "queue-state-unavailable",
    );
  });

  it("hashes semantically identical queue objects independently of key order", () => {
    const first = [{ id: "q", text: "继续", context: { prompt: "继续", turnTrigger: "user" } }];
    const reordered = [{ context: { turnTrigger: "user", prompt: "继续" }, text: "继续", id: "q" }];

    expect(hashQueue(first)).toBe(hashQueue(reordered));
  });

  it("deduplicates completed operations and releases failed claims", () => {
    let now = 1_000;
    const store = new BridgeStore(() => now);

    expect(store.beginIdempotent("device-1:thread-1", "message-1", 500)).toEqual({
      replayed: false,
    });
    expect(() =>
      store.beginIdempotent("device-1:thread-1", "message-1", 500),
    ).toThrow("idempotency-in-progress");
    store.completeIdempotent("device-1:thread-1", "message-1", { ok: true });
    expect(store.beginIdempotent("device-1:thread-1", "message-1", 500)).toEqual({
      replayed: true,
      result: { ok: true },
    });

    expect(store.beginIdempotent("device-1:thread-1", "message-2", 500)).toEqual({
      replayed: false,
    });
    store.releaseIdempotent("device-1:thread-1", "message-2");
    expect(store.beginIdempotent("device-1:thread-1", "message-2", 500)).toEqual({
      replayed: false,
    });

    now = 1_501;
    expect(store.beginIdempotent("device-1:thread-1", "message-1", 500)).toEqual({
      replayed: false,
    });
  });
});
