import { describe, expect, it } from "vitest";

import {
  CodexIpcAdapter,
  CURRENT_DESKTOP_ADAPTER,
  type IpcRequestTransport,
} from "../src/ipc/adapter.js";
import type { IpcFrame } from "../src/ipc/types.js";

class FakeTransport implements IpcRequestTransport {
  readonly calls: Array<{
    method: string;
    params: unknown;
    options: { version: number; targetClientId?: string };
  }> = [];
  readonly broadcasts: Array<{
    method: string;
    params: unknown;
    options: { version: number; targetClientIds?: string[] };
  }> = [];

  async request(
    method: string,
    params: unknown,
    options: { version: number; targetClientId?: string },
  ): Promise<IpcFrame> {
    this.calls.push({ method, params, options });
    if (method === "thread-owner-discovery") {
      return {
        type: "response",
        requestId: "owner-request",
        resultType: "success",
        handledByClientId: "desktop-owner",
        result: { supportsUntrustedAppInput: true },
      };
    }
    return {
      type: "response",
      requestId: "action-request",
      resultType: "success",
      result: { ok: true },
    };
  }

  async broadcast(
    method: string,
    params: unknown,
    options: { version: number; targetClientIds?: string[] },
  ): Promise<void> {
    this.broadcasts.push({ method, params, options });
  }
}

describe("current Codex Desktop IPC adapter", () => {
  it("pins the observed 26.901 method versions", () => {
    expect(CURRENT_DESKTOP_ADAPTER.packageVersion).toBe("26.901.1978.0");
    expect(CURRENT_DESKTOP_ADAPTER.methods).toMatchObject({
      "thread-stream-state-changed": 11,
      "thread-follower-start-turn": 2,
      "thread-follower-interrupt-turn": 4,
      "thread-follower-edit-last-user-turn": 2,
      "thread-follower-update-thread-settings": 1,
      "thread-follower-set-queued-follow-ups-state": 1,
      "thread-follower-command-approval-decision": 1,
      "thread-follower-submit-user-input": 1,
    });
  });

  it("routes a command decision only to the live desktop owner", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );

    await adapter.respondToApproval("thread-1", "request-7", "command", "accept");

    expect(transport.calls).toEqual([
      {
        method: "thread-owner-discovery",
        params: { hostId: "local", conversationId: "thread-1" },
        options: { version: 1 },
      },
      {
        method: "thread-follower-command-approval-decision",
        params: {
          conversationId: "thread-1",
          requestId: "request-7",
          decision: "accept",
        },
        options: { version: 1, targetClientId: "desktop-owner" },
      },
    ]);
  });

  it("keeps known protocol operations available when the Desktop version changes", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.902.1.0",
    );

    expect(adapter.compatibility).toMatchObject({
      supported: true,
      verified: false,
      mode: "best-effort",
    });
    await expect(adapter.interrupt("thread-1", "turn-1")).resolves.toMatchObject({
      resultType: "success",
    });
    expect(transport.calls.at(-1)?.options.version).toBe(4);
  });

  it("keeps known protocol operations available when the bundled CLI changes", async () => {
    const adapter = new CodexIpcAdapter(
      new FakeTransport(),
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
      "0.154.0",
    );

    expect(adapter.compatibility).toMatchObject({
      supported: true,
      verified: false,
      mode: "best-effort",
      expectedCli: "0.153.0-alpha.5",
      installedCli: "0.154.0",
    });
    await expect(adapter.startTurn("thread-1", "hello")).resolves.toMatchObject({
      resultType: "success",
    });
  });

  it("registers as a follower before requesting an owner snapshot", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );

    await adapter.loadHistory("thread-1");

    expect(transport.broadcasts).toEqual([{
      method: "thread-stream-following-changed",
      params: { conversationId: "thread-1", hostId: "local", following: true },
      options: { version: 1, targetClientIds: ["desktop-owner"] },
    }]);
    expect(transport.calls.at(-1)).toEqual({
      method: "thread-follower-load-complete-history",
      params: { conversationId: "thread-1" },
      options: { version: 1, targetClientId: "desktop-owner" },
    });
  });

  it("sends safe local attachments in the observed start-turn structure", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );

    await adapter.startTurn("thread-1", "看一下附件", {
      clientUserMessageId: "message-1",
      attachments: [
        {
          attachmentId: "image-1",
          path: "C:\\bridge\\image.png",
          fsPath: "C:\\bridge\\image.png",
          label: "image.png",
          kind: "image",
        },
        {
          attachmentId: "file-1",
          path: "C:\\bridge\\notes.md",
          fsPath: "C:\\bridge\\notes.md",
          label: "notes.md",
          kind: "file",
        },
      ],
    });

    expect(transport.calls.at(-1)).toEqual({
      method: "thread-follower-start-turn",
      options: { version: 2, targetClientId: "desktop-owner" },
      params: {
        conversationId: "thread-1",
        turnStart: {
          request: {
            threadId: "thread-1",
            input: [
              { type: "text", text: "看一下附件", text_elements: [] },
              { type: "localImage", path: "C:\\bridge\\image.png" },
              { type: "mention", name: "notes.md", path: "C:\\bridge\\notes.md" },
            ],
            clientUserMessageId: "message-1",
            turnTrigger: "user",
          },
          context: {
            attachments: [
              {
                path: "C:\\bridge\\image.png",
                fsPath: "C:\\bridge\\image.png",
                label: "image.png",
              },
            ],
            commentAttachments: [],
            inheritThreadSettings: true,
            responseItems: [],
            additionalContext: {
              "codex_remote_attachment:file-1": {
                kind: "application",
                value: "# Attached file\nThe user attached this local file: C:\\bridge\\notes.md",
              },
            },
          },
        },
      },
    });
  });

  it("sends the complete observed steer envelope with a restorable message", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );

    await adapter.steer("thread-1", "补充截图", {
      cwd: "C:\\repo",
      serviceTier: "priority",
      clientUserMessageId: "client-message-1",
      restoreMessageId: "restore-message-1",
      createdAt: 1234,
      attachments: [{
        attachmentId: "image-2",
        path: "C:\\bridge\\screen.png",
        fsPath: "C:\\bridge\\screen.png",
        label: "screen.png",
        kind: "image",
      }],
    });

    expect(transport.calls.at(-1)).toEqual({
      method: "thread-follower-steer-turn",
      options: { version: 1, targetClientId: "desktop-owner" },
      params: {
        conversationId: "thread-1",
        clientUserMessageId: "client-message-1",
        input: [
          { type: "text", text: "补充截图", text_elements: [] },
          { type: "localImage", path: "C:\\bridge\\screen.png" },
        ],
        serviceTier: "priority",
        attachments: [{
          path: "C:\\bridge\\screen.png",
          fsPath: "C:\\bridge\\screen.png",
          label: "screen.png",
        }],
        additionalContext: {},
        toolOutput: null,
        restoreMessage: {
          id: "restore-message-1",
          text: "补充截图",
          context: {
            prompt: "补充截图",
            turnTrigger: "user",
            addedFiles: [],
            fileAttachments: [],
            ideContext: null,
            imageAttachments: [],
            workspaceRoots: ["C:\\repo"],
          },
          cwd: "C:\\repo",
          createdAt: 1234,
        },
      },
    });
  });

  it("updates only model and effort through the live owner", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );

    await adapter.updateThreadSettings("thread-1", {
      model: "gpt-5.2-codex",
      effort: "high",
    });

    expect(transport.calls.at(-1)).toEqual({
      method: "thread-follower-update-thread-settings",
      params: {
        conversationId: "thread-1",
        threadSettings: { model: "gpt-5.2-codex", effort: "high" },
      },
      options: { version: 1, targetClientId: "desktop-owner" },
    });
  });

  it("replaces the owner's complete queued follow-up state", async () => {
    const transport = new FakeTransport();
    const adapter = new CodexIpcAdapter(
      transport,
      CURRENT_DESKTOP_ADAPTER,
      "26.901.1978.0",
    );
    const messages = [{ id: "queued-1", text: "下一步", cwd: "C:\\repo" }];

    await adapter.setQueuedFollowUps("thread-1", messages);

    expect(transport.calls.at(-1)).toEqual({
      method: "thread-follower-set-queued-follow-ups-state",
      params: {
        conversationId: "thread-1",
        state: { "thread-1": messages },
      },
      options: { version: 1, targetClientId: "desktop-owner" },
    });
  });
});
