import type { IpcFrame } from "./types.js";

export type DesktopAdapterDefinition = {
  packageVersion: string;
  cliVersion: string;
  methods: Readonly<Record<string, number>>;
};

export const CURRENT_DESKTOP_ADAPTER: DesktopAdapterDefinition = {
  packageVersion: "26.901.1978.0",
  cliVersion: "0.153.0-alpha.5",
  methods: Object.freeze({
    "thread-stream-state-changed": 11,
    "thread-stream-following-changed": 1,
    "thread-stream-following-status-requested": 1,
    "thread-read-state-changed": 2,
    "thread-archived": 2,
    "thread-unarchived": 1,
    "thread-owner-discovery": 1,
    "thread-follower-start-turn": 2,
    "thread-follower-load-complete-history": 1,
    "thread-follower-compact-thread": 1,
    "thread-follower-steer-turn": 1,
    "thread-follower-interrupt-turn": 4,
    "thread-follower-update-thread-settings": 1,
    "thread-follower-edit-last-user-turn": 2,
    "thread-follower-command-approval-decision": 1,
    "thread-follower-file-approval-decision": 1,
    "thread-follower-permissions-request-approval-response": 1,
    "thread-follower-submit-user-input": 1,
    "thread-follower-submit-mcp-server-elicitation-response": 1,
    "thread-follower-set-queued-follow-ups-state": 1,
    "thread-queued-followups-changed": 1,
  }),
};

export interface IpcRequestTransport {
  request(
    method: string,
    params: unknown,
    options: { version: number; targetClientId?: string },
  ): Promise<IpcFrame>;
  broadcast?(
    method: string,
    params: unknown,
    options: { version: number; targetClientIds?: string[] },
  ): Promise<void>;
}

export type ApprovalKind = "command" | "file" | "permission";
export type ApprovalDecision = "accept" | "decline" | "cancel";

export type DesktopAttachment = {
  attachmentId: string;
  path: string;
  fsPath: string;
  label: string;
  kind: "image" | "file";
};

export type TurnMessageOptions = {
  attachments?: DesktopAttachment[];
  clientUserMessageId?: string;
  cwd?: string;
  serviceTier?: string | null;
  restoreMessageId?: string;
  createdAt?: number;
};

export type ThreadSettingsUpdate = {
  model: string;
  effort: string;
};

export class CodexIpcAdapter {
  constructor(
    private readonly transport: IpcRequestTransport,
    readonly definition: DesktopAdapterDefinition,
    private readonly installedPackageVersion: string | null,
    private readonly installedCliVersion: string | null = definition.cliVersion,
  ) {}

  get writable(): boolean {
    return true;
  }

  get compatibility(): {
    supported: boolean;
    verified: boolean;
    mode: "verified" | "best-effort";
    expected: string;
    installed: string | null;
    expectedCli: string;
    installedCli: string | null;
  } {
    const verified =
      this.installedPackageVersion === this.definition.packageVersion &&
      this.installedCliVersion === this.definition.cliVersion;
    return {
      supported: true,
      verified,
      mode: verified ? "verified" : "best-effort",
      expected: this.definition.packageVersion,
      installed: this.installedPackageVersion,
      expectedCli: this.definition.cliVersion,
      installedCli: this.installedCliVersion,
    };
  }

  async discoverOwner(threadId: string): Promise<string> {
    const response = await this.transport.request(
      "thread-owner-discovery",
      { hostId: "local", conversationId: threadId },
      { version: this.version("thread-owner-discovery") },
    );
    const owner = response.handledByClientId;
    if (response.resultType !== "success" || !owner) {
      throw new Error(response.error ?? "desktop-owner-unavailable");
    }
    return owner;
  }

  async loadHistory(threadId: string): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    if (!this.transport.broadcast) {
      throw new Error("ipc-broadcast-unavailable");
    }
    await this.transport.broadcast(
      "thread-stream-following-changed",
      { conversationId: threadId, hostId: "local", following: true },
      {
        version: this.version("thread-stream-following-changed"),
        targetClientIds: [owner],
      },
    );
    return this.ownerRequest(
      owner,
      "thread-follower-load-complete-history",
      { conversationId: threadId },
    );
  }

  async startTurn(
    threadId: string,
    text: string,
    options: TurnMessageOptions = {},
  ): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    const clientUserMessageId = options.clientUserMessageId ?? crypto.randomUUID();
    const attachments = normalizeAttachments(options.attachments ?? []);
    const input = buildTurnInput(text, options.attachments ?? []);
    const additionalContext = buildAdditionalContext(options.attachments ?? []);
    return this.ownerRequest(owner, "thread-follower-start-turn", {
      conversationId: threadId,
      turnStart: {
        request: {
          threadId,
          input,
          clientUserMessageId,
          turnTrigger: "user",
        },
        context: {
          attachments,
          commentAttachments: [],
          inheritThreadSettings: true,
          responseItems: [],
          additionalContext,
        },
      },
    });
  }

  async steer(
    threadId: string,
    text: string,
    options: TurnMessageOptions = {},
  ): Promise<IpcFrame> {
    if (!options.cwd) throw new Error("thread-cwd-required");
    const owner = await this.discoverOwner(threadId);
    const clientUserMessageId = options.clientUserMessageId ?? crypto.randomUUID();
    const input = buildTurnInput(text, options.attachments ?? []);
    const restoreMessageId = options.restoreMessageId ?? crypto.randomUUID();
    const createdAt = options.createdAt ?? Date.now();
    return this.ownerRequest(owner, "thread-follower-steer-turn", {
      conversationId: threadId,
      clientUserMessageId,
      input,
      serviceTier: options.serviceTier ?? null,
      attachments: normalizeAttachments(options.attachments ?? []),
      additionalContext: buildAdditionalContext(options.attachments ?? []),
      toolOutput: null,
      restoreMessage: {
        id: restoreMessageId,
        text,
        context: {
          prompt: text,
          turnTrigger: "user",
          addedFiles: [],
          fileAttachments: [],
          ideContext: null,
          imageAttachments: [],
          workspaceRoots: [options.cwd],
        },
        cwd: options.cwd,
        createdAt,
      },
    });
  }

  async updateThreadSettings(
    threadId: string,
    settings: ThreadSettingsUpdate,
  ): Promise<IpcFrame> {
    if (!settings.model || !settings.effort) {
      throw new Error("invalid-thread-settings");
    }
    const owner = await this.discoverOwner(threadId);
    return this.ownerRequest(owner, "thread-follower-update-thread-settings", {
      conversationId: threadId,
      threadSettings: {
        model: settings.model,
        effort: settings.effort,
      },
    });
  }

  async setQueuedFollowUps(
    threadId: string,
    messages: Array<Record<string, unknown>>,
  ): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    return this.ownerRequest(owner, "thread-follower-set-queued-follow-ups-state", {
      conversationId: threadId,
      state: { [threadId]: structuredClone(messages) },
    });
  }

  async interrupt(threadId: string, expectedTurnId?: string): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    return this.ownerRequest(owner, "thread-follower-interrupt-turn", {
      conversationId: threadId,
      mode: "user-stop",
      expectedTurnId: expectedTurnId ?? null,
    });
  }

  async respondToApproval(
    threadId: string,
    requestId: string,
    kind: ApprovalKind,
    decision: ApprovalDecision,
  ): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    const method =
      kind === "command"
        ? "thread-follower-command-approval-decision"
        : kind === "file"
          ? "thread-follower-file-approval-decision"
          : "thread-follower-permissions-request-approval-response";
    const params =
      kind === "permission"
        ? { conversationId: threadId, requestId, response: { decision } }
        : { conversationId: threadId, requestId, decision };
    return this.ownerRequest(owner, method, params);
  }

  async respondToUserInput(
    threadId: string,
    requestId: string,
    response: Record<string, unknown>,
  ): Promise<IpcFrame> {
    const owner = await this.discoverOwner(threadId);
    return this.ownerRequest(owner, "thread-follower-submit-user-input", {
      conversationId: threadId,
      requestId,
      response,
    });
  }

  private async ownerRequest(
    owner: string,
    method: string,
    params: unknown,
  ): Promise<IpcFrame> {
    const response = await this.transport.request(method, params, {
      version: this.version(method),
      targetClientId: owner,
    });
    if (response.resultType !== "success") {
      throw new Error(response.error ?? `${method}-failed`);
    }
    return response;
  }

  private version(method: string): number {
    const version = this.definition.methods[method];
    if (version === undefined) throw new Error(`unsupported-ipc-method:${method}`);
    return version;
  }

}

function normalizeAttachments(
  attachments: DesktopAttachment[],
): Array<{ path: string; fsPath: string; label: string }> {
  return attachments.filter((attachment) => attachment.kind === "image").map((attachment) => ({
    path: attachment.path,
    fsPath: attachment.fsPath,
    label: attachment.label,
  }));
}

function buildTurnInput(
  text: string,
  attachments: DesktopAttachment[],
): Array<Record<string, unknown>> {
  const input: Array<Record<string, unknown>> = [];
  if (text.length > 0) input.push({ type: "text", text, text_elements: [] });
  for (const attachment of attachments) {
    if (attachment.kind === "image") {
      input.push({ type: "localImage", path: attachment.path });
    } else {
      input.push({
        type: "mention",
        name: attachment.label,
        path: attachment.path,
      });
    }
  }
  if (input.length === 0 && attachments.length === 0) {
    throw new Error("message-empty");
  }
  return input;
}

function buildAdditionalContext(
  attachments: DesktopAttachment[],
): Record<string, { kind: "application"; value: string }> {
  return Object.fromEntries(
    attachments
      .filter((attachment) => attachment.kind === "file")
      .map((attachment) => [
        `codex_remote_attachment:${attachment.attachmentId}`,
        {
          kind: "application" as const,
          value: `# Attached file\nThe user attached this local file: ${attachment.path}`,
        },
      ]),
  );
}
