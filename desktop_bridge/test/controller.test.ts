import { describe, expect, it } from "vitest";

import type {
  ApprovalDecision,
  ApprovalKind,
  ThreadSettingsUpdate,
  TurnMessageOptions,
} from "../src/ipc/adapter.js";
import type { IpcFrame } from "../src/ipc/types.js";
import {
  BridgeController,
  PUSH_REQUEST_PROMPT,
  type CodexControlPort,
} from "../src/bridge/controller.js";
import { BridgeStore } from "../src/domain/store.js";

class FakeControl implements CodexControlPort {
  calls: Array<[string, ...unknown[]]> = [];
  lastStartOptions: TurnMessageOptions | undefined;
  lastSteerOptions: TurnMessageOptions | undefined;
  writable = true;
  compatibility = {
    supported: true,
    expected: "26.901.1978.0",
    installed: "26.901.1978.0",
  };

  constructor(private readonly failures: {
    history?: number;
    settings?: boolean;
    start?: boolean;
  } = {}) {}

  async loadHistory(threadId: string): Promise<IpcFrame> {
    this.calls.push(["loadHistory", threadId]);
    if ((this.failures.history ?? 0) > 0) {
      this.failures.history = (this.failures.history ?? 0) - 1;
      throw new Error("desktop-owner-unavailable");
    }
    return success();
  }

  async startTurn(
    threadId: string,
    text: string,
    options?: TurnMessageOptions,
  ): Promise<IpcFrame> {
    this.lastStartOptions = options;
    this.calls.push(["startTurn", threadId, text]);
    if (this.failures.start) throw new Error("start-rejected");
    return success();
  }

  async steer(
    threadId: string,
    text: string,
    options?: TurnMessageOptions,
  ): Promise<IpcFrame> {
    this.lastSteerOptions = options;
    this.calls.push(["steer", threadId, text]);
    return success();
  }

  async updateThreadSettings(
    threadId: string,
    settings: ThreadSettingsUpdate,
  ): Promise<IpcFrame> {
    this.calls.push(["settings", threadId, settings]);
    if (this.failures.settings) throw new Error("settings-rejected");
    return success();
  }

  async setQueuedFollowUps(
    threadId: string,
    messages: Array<Record<string, unknown>>,
  ): Promise<IpcFrame> {
    this.calls.push(["queue", threadId, messages]);
    return success();
  }

  async interrupt(threadId: string, turnId?: string): Promise<IpcFrame> {
    this.calls.push(["interrupt", threadId, turnId]);
    return success();
  }

  async respondToApproval(
    threadId: string,
    requestId: string,
    kind: ApprovalKind,
    decision: ApprovalDecision,
  ): Promise<IpcFrame> {
    this.calls.push(["approval", threadId, requestId, kind, decision]);
    return success();
  }

  async respondToUserInput(
    threadId: string,
    requestId: string,
    response: Record<string, unknown>,
  ): Promise<IpcFrame> {
    this.calls.push(["userInput", threadId, requestId, response]);
    return success();
  }
}

describe("BridgeController", () => {
  it("steers an active turn and starts a new idle turn", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);
    controller.ingestIpcFrame(snapshot("active-thread", 1, "active"));
    controller.ingestIpcFrame(snapshot("idle-thread", 1, "idle"));

    await controller.sendMessage("active-thread", "补充测试");
    await controller.sendMessage("idle-thread", "开始处理");

    expect(control.calls).toEqual([
      ["steer", "active-thread", "补充测试"],
      ["startTurn", "idle-thread", "开始处理"],
    ]);
    expect(control.lastSteerOptions).toMatchObject({
      cwd: "C:\\repo",
      serviceTier: null,
    });
  });

  it("honors explicit delivery and rejects a stale active turn", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);
    controller.ingestIpcFrame(snapshot("thread-1", 1, "active"));

    await expect(controller.sendMessage("thread-1", "补充", {
      delivery: "start",
    })).rejects.toThrow("turn-already-active");
    await expect(controller.sendMessage("thread-1", "补充", {
      delivery: "steer",
      expectedTurnId: "stale-turn",
    })).rejects.toThrow("stale-turn-id");
    const result = await controller.sendMessage("thread-1", "补充", {
      delivery: "steer",
      expectedTurnId: "turn-thread-1",
    });

    expect(result.delivery).toBe("steer");
    expect(control.calls).toEqual([["steer", "thread-1", "补充"]]);
  });

  it("replaces a hash-checked queue and can cancel a mirrored message", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);
    controller.ingestIpcFrame(snapshot("thread-1", 1, "active"));
    controller.ingestIpcFrame({
      type: "broadcast",
      method: "thread-queued-followups-changed",
      version: 1,
      params: {
        conversationId: "thread-1",
        messages: [{ id: "queued-1", text: "已有消息", cwd: "C:\\repo" }],
      },
    });
    const initial = controller.getQueue("thread-1");

    await expect(controller.sendMessage("thread-1", "下一条", {
      delivery: "queue",
      expectedQueueHash: "stale",
    })).rejects.toThrow("queue-hash-conflict");
    const queued = await controller.sendMessage("thread-1", "下一条", {
      delivery: "queue",
      expectedQueueHash: initial.hash,
    });
    expect(queued).toMatchObject({ delivery: "queue" });
    expect(queued.queuedMessageId).toBeTruthy();
    const replacement = control.calls.at(-1)?.[2] as Array<Record<string, unknown>>;
    expect(replacement).toHaveLength(2);
    expect(replacement[1]).toMatchObject({
      text: "下一条",
      cwd: "C:\\repo",
      context: {
        prompt: "下一条",
        workspaceRoots: ["C:\\repo"],
      },
    });

    const cancelled = await controller.cancelQueuedMessage(
      "thread-1",
      "queued-1",
      initial.hash,
    );
    expect(cancelled.queueHash).toHaveLength(64);
    expect(control.calls.at(-1)).toEqual(["queue", "thread-1", []]);
  });

  it("validates model effort against the dynamic model list", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store, {
      async listThreads() { return []; },
      async listModels() {
        return [{
          id: "gpt-5.2-codex",
          supportedReasoningEfforts: [
            { reasoningEffort: "medium" },
            { reasoningEffort: "high" },
          ],
        }];
      },
    });
    controller.ingestIpcFrame(snapshot("thread-1", 1, "idle"));

    await expect(controller.updateThreadSettings("thread-1", {
      model: "gpt-5.2-codex",
      effort: "ultra",
    })).rejects.toThrow("unsupported-reasoning-effort");
    await controller.updateThreadSettings("thread-1", {
      model: "gpt-5.2-codex",
      effort: "high",
    });

    expect(control.calls).toContainEqual([
      "settings",
      "thread-1",
      { model: "gpt-5.2-codex", effort: "high" },
    ]);
  });

  it("materializes an empty task, waits for Desktop, then sends the real prompt", async () => {
    const store = new BridgeStore();
    const control = new FakeControl({ history: 2 });
    const materializeCalls: Array<Record<string, unknown>> = [];
    const controller = new BridgeController(
      control,
      store,
      modelCatalog(),
      {
        async materialize(input) {
          materializeCalls.push(input);
          return {
            threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
            projectId: "project-1",
            permissionProfile: ":workspace",
          };
        },
      },
      { timeoutMs: 3, pollIntervalMs: 1, sleep: async () => undefined },
    );

    expect(controller.capabilities).toMatchObject({ taskCreation: true });
    const result = await controller.createTask({
      cwd: " C:\\repo ",
      prompt: " 完成 Android 界面 ",
      model: "gpt-5.2-codex",
      reasoningEffort: "high",
    });

    expect(result).toEqual({
      threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      promptAccepted: true,
      stage: "complete",
    });
    expect(materializeCalls).toEqual([{
      cwd: "C:\\repo",
      model: "gpt-5.2-codex",
      effort: "high",
    }]);
    expect(JSON.stringify(materializeCalls)).not.toContain("完成 Android 界面");
    expect(control.calls.map(([name]) => name)).toEqual([
      "loadHistory",
      "loadHistory",
      "loadHistory",
      "settings",
      "startTurn",
    ]);
    expect(control.calls.at(-1)).toEqual([
      "startTurn",
      "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      "完成 Android 界面",
    ]);
  });

  it("returns a partial created task when Desktop owner handoff times out", async () => {
    const store = new BridgeStore();
    const control = new FakeControl({ history: Number.POSITIVE_INFINITY });
    let materialized = 0;
    const controller = new BridgeController(
      control,
      store,
      modelCatalog(),
      {
        async materialize() {
          materialized += 1;
          return {
            threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
            projectId: "project-1",
            permissionProfile: ":workspace",
          };
        },
      },
      { timeoutMs: 2, pollIntervalMs: 1, sleep: async () => undefined },
    );

    const result = await controller.createTask({
      cwd: "C:\\repo",
      prompt: "真实任务",
      model: "gpt-5.2-codex",
      reasoningEffort: "high",
    });

    expect(result).toMatchObject({
      threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      promptAccepted: false,
      stage: "owner",
      error: expect.stringContaining("task-owner-handoff-timeout"),
    });
    expect(materialized).toBe(1);
    expect(control.calls.filter(([name]) => name === "loadHistory")).toHaveLength(2);
    expect(control.calls.some(([name]) => name === "startTurn")).toBe(false);
  });

  it("turns a push request into a fixed Codex instruction", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);
    controller.ingestIpcFrame(snapshot("thread-1", 1, "idle"));

    await controller.requestPush("thread-1");

    expect(control.calls).toEqual([
      ["startTurn", "thread-1", PUSH_REQUEST_PROMPT],
    ]);
    expect(PUSH_REQUEST_PROMPT).toContain("不要强制推送");
    expect(PUSH_REQUEST_PROMPT).toContain("敏感信息");
  });

  it("lists the live desktop owner before read-only history", async () => {
    const store = new BridgeStore();
    const controller = new BridgeController(new FakeControl(), store, {
      async listThreads() {
        return [
          { id: "history-thread", name: "Earlier task", status: "idle" },
          { id: "live-thread", name: "Duplicate live task", status: "idle" },
        ];
      },
    });
    controller.ingestIpcFrame(snapshot("live-thread", 4, "active"));

    await expect(controller.listTasks()).resolves.toMatchObject([
      { threadId: "live-thread", ownerAvailable: true },
      { threadId: "history-thread", ownerAvailable: false },
    ]);
  });

  it("auto-follows an owner status announcement before the phone selects a task", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);

    controller.ingestIpcFrame({
      type: "broadcast",
      method: "thread-stream-following-status-requested",
      version: 1,
      sourceClientId: "desktop-owner",
      params: { hostId: "local", conversationId: "desktop-thread" },
    });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(control.calls).toEqual([["loadHistory", "desktop-thread"]]);
  });

  it("preserves task update times for completions missed while the phone was offline", async () => {
    const store = new BridgeStore();
    const controller = new BridgeController(new FakeControl(), store, {
      async listThreads() {
        return [
          { id: "history-thread", status: "idle", updatedAt: 1_800_000_010 },
          { id: "live-thread", status: "idle", updatedAt: 1_800_000_020 },
        ];
      },
    });
    controller.ingestIpcFrame(snapshot("live-thread", 4, "idle"));
    await expect(controller.listTasks()).resolves.toMatchObject([
      { threadId: "live-thread", updatedAt: 1_800_000_020_000 },
      { threadId: "history-thread", updatedAt: 1_800_000_010_000 },
    ]);
  });

  it("restores subscriptions after IPC reconnect even with cached task history", async () => {
    const control = new FakeControl();
    const controller = new BridgeController(control, new BridgeStore());
    controller.ingestIpcFrame(snapshot("live-thread", 4, "idle"));
    await controller.restoreFollowing();
    expect(control.calls).toEqual([["loadHistory", "live-thread"]]);
  });

  it("accepts parseable stream broadcasts from an unverified protocol version", () => {
    const store = new BridgeStore();
    const controller = new BridgeController(new FakeControl(), store);

    controller.ingestIpcFrame({
      ...snapshot("thread-1", 1, "idle"),
      version: 12,
    });

    expect(store.getThread("thread-1")?.revision).toBe(1);
    expect(store.eventsAfter(0)).toEqual(expect.arrayContaining([
      expect.objectContaining({
        type: "protocol.unverified",
        payload: expect.objectContaining({ expectedVersion: 11, receivedVersion: 12 }),
      }),
    ]));
  });

  it("presents a compact task timeline and atomically answers user input", async () => {
    const store = new BridgeStore();
    const control = new FakeControl();
    const controller = new BridgeController(control, store);
    controller.ingestIpcFrame({
      ...snapshot("thread-1", 3, "active"),
      params: {
        conversationId: "thread-1",
        change: {
          type: "snapshot",
          revision: 3,
          conversationState: {
            title: "Remote test",
            threadRuntimeStatus: { type: "active" },
            turns: [{
              id: "turn-1",
              status: "inProgress",
              items: [
                { id: "u1", type: "userMessage", content: [{ type: "text", text: "继续" }] },
                { id: "a1", type: "agentMessage", text: "正在检查" },
              ],
            }],
            requests: [{
              id: "input-1",
              method: "item/tool/requestUserInput",
              params: { questions: [{ id: "choice", question: "选择？" }] },
            }],
          },
        },
      },
    });

    await expect(controller.getTaskDetail("thread-1")).resolves.toMatchObject({
      title: "Remote test",
      status: "active",
      items: [
        { kind: "user", text: "继续" },
        { kind: "assistant", text: "正在检查" },
      ],
    });
    const response = { answers: { choice: { answers: ["继续"] } } };
    await controller.respondToUserInput("input-1", response, "android:req-1");
    expect(control.calls).toContainEqual([
      "userInput",
      "thread-1",
      "input-1",
      response,
    ]);
    await expect(
      controller.respondToUserInput("input-1", response, "ones:req-2"),
    ).rejects.toThrow("approval-not-found");
  });
});

function snapshot(threadId: string, revision: number, status: string): IpcFrame {
  return {
    type: "broadcast",
    method: "thread-stream-state-changed",
    version: 11,
    params: {
      conversationId: threadId,
      hostId: "local",
      change: {
        type: "snapshot",
        revision,
        conversationState: {
          cwd: "C:\\repo",
          threadRuntimeStatus: {
            type: status,
            ...(status === "active" ? { turnId: `turn-${threadId}` } : {}),
          },
          requests: [],
        },
      },
    },
  };
}

function success(): IpcFrame {
  return { type: "response", resultType: "success", requestId: "ok" };
}

function modelCatalog() {
  return {
    async listThreads() { return []; },
    async listModels() {
      return [{
        id: "gpt-5.2-codex",
        supportedReasoningEfforts: [
          { reasoningEffort: "medium" },
          { reasoningEffort: "high" },
        ],
      }];
    },
  };
}
