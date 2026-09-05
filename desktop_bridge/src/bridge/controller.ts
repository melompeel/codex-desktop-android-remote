import { randomUUID } from "node:crypto";

import type {
  MaterializedTask,
  MaterializeTaskInput,
} from "../catalog/task-creator.js";
import type {
  ApprovalDecision,
  ApprovalKind,
  DesktopAttachment,
  ThreadSettingsUpdate,
  TurnMessageOptions,
} from "../ipc/adapter.js";
import type { IpcFrame } from "../ipc/types.js";
import {
  BridgeStore,
  hashQueue,
  type QueuedFollowUpMessage,
  type StreamChange,
  type ThreadQueue,
  type ThreadStream,
} from "../domain/store.js";
import {
  presentThread,
  presentThreadDiff,
  presentThreadMetadata,
  type GitInfoSummary,
  type TaskDetail,
  type TaskDiff,
  type ThreadSettingsSummary,
} from "../domain/presentation.js";

export const PUSH_REQUEST_PROMPT = [
  "请对当前仓库执行受控的提交与推送流程：先检查 git status、相关 diff 和未跟踪文件，",
  "运行仓库合适的测试、检查或构建命令；只暂存本次任务直接相关的文件，",
  "不得包含密码、令牌、私钥、.env、截图、缓存或临时产物。",
  "测试失败、发现敏感信息、分支没有 upstream、工作范围不清楚或远端认证失败时立即停止并向我说明。",
  "条件全部满足后使用简洁中文提交信息提交，并只推送当前分支到其 upstream。不要强制推送，不要改写历史。",
].join("");

export interface CodexControlPort {
  readonly writable: boolean;
  readonly compatibility: {
    supported: boolean;
    verified?: boolean;
    mode?: "verified" | "best-effort";
    expected: string;
    installed: string | null;
  };
  loadHistory(threadId: string): Promise<IpcFrame>;
  startTurn(
    threadId: string,
    text: string,
    options?: TurnMessageOptions,
  ): Promise<IpcFrame>;
  steer(
    threadId: string,
    text: string,
    options?: TurnMessageOptions,
  ): Promise<IpcFrame>;
  interrupt(threadId: string, expectedTurnId?: string): Promise<IpcFrame>;
  updateThreadSettings?(
    threadId: string,
    settings: ThreadSettingsUpdate,
  ): Promise<IpcFrame>;
  setQueuedFollowUps?(
    threadId: string,
    messages: Array<Record<string, unknown>>,
  ): Promise<IpcFrame>;
  respondToApproval(
    threadId: string,
    requestId: string,
    kind: ApprovalKind,
    decision: ApprovalDecision,
  ): Promise<IpcFrame>;
  respondToUserInput(
    threadId: string,
    requestId: string,
    response: Record<string, unknown>,
  ): Promise<IpcFrame>;
}

export type TaskSummary = {
  threadId: string;
  title: string;
  status: string;
  revision: number;
  pendingApprovals: number;
  ownerAvailable: boolean;
  cwd?: string;
  cwdGroupKey?: string;
  cwdGroupLabel?: string;
  gitInfo?: GitInfoSummary;
  settings?: ThreadSettingsSummary;
  activeTurnId?: string;
};

export type TaskListQuery = {
  limit?: number;
  cursor?: string;
  archived?: boolean;
  searchTerm?: string;
};

export type TaskPage = {
  tasks: TaskSummary[];
  nextCursor: string | null;
};

export type CatalogThreadPage = {
  data: Array<Record<string, unknown>>;
  nextCursor: string | null;
};

export type MessageDelivery = "auto" | "start" | "steer" | "queue";

export type SendMessageOptions = {
  delivery?: MessageDelivery;
  expectedTurnId?: string;
  expectedQueueHash?: string;
  attachments?: DesktopAttachment[];
};

export type SendMessageResult = {
  delivery: Exclude<MessageDelivery, "auto">;
  clientUserMessageId?: string;
  queuedMessageId?: string;
  queueHash?: string;
};

export interface TaskCatalogPort {
  listThreads(limit?: number): Promise<Array<Record<string, unknown>>>;
  listThreadPage?(query: TaskListQuery): Promise<CatalogThreadPage>;
  readThread?(threadId: string): Promise<Record<string, unknown> | null>;
  listModels?(): Promise<Array<Record<string, unknown>>>;
}

export interface TaskCreatorPort {
  materialize(input: MaterializeTaskInput): Promise<MaterializedTask>;
}

export type CreateTaskInput = {
  cwd: string;
  prompt: string;
  model: string;
  reasoningEffort: string;
};

export type CreateTaskResult = {
  threadId: string;
  promptAccepted: boolean;
  stage: "owner" | "settings" | "prompt" | "complete";
  error?: string;
};

export type OwnerHandoffOptions = {
  timeoutMs?: number;
  pollIntervalMs?: number;
  sleep?: (milliseconds: number) => Promise<void>;
};

export class BridgeController {
  private readonly ownerHandoffTimeoutMs: number;
  private readonly ownerHandoffPollIntervalMs: number;
  private readonly sleep: (milliseconds: number) => Promise<void>;

  constructor(
    private readonly control: CodexControlPort,
    readonly store: BridgeStore,
    private readonly catalog?: TaskCatalogPort,
    private readonly taskCreator?: TaskCreatorPort,
    ownerHandoff: OwnerHandoffOptions = {},
  ) {
    this.ownerHandoffTimeoutMs = ownerHandoff.timeoutMs ?? 30_000;
    this.ownerHandoffPollIntervalMs = ownerHandoff.pollIntervalMs ?? 250;
    this.sleep = ownerHandoff.sleep ?? ((milliseconds) =>
      new Promise((resolve) => setTimeout(resolve, milliseconds)));
    if (
      this.ownerHandoffTimeoutMs <= 0 ||
      this.ownerHandoffPollIntervalMs <= 0
    ) {
      throw new Error("invalid-owner-handoff-options");
    }
  }

  get compatibility(): CodexControlPort["compatibility"] {
    return this.control.compatibility;
  }

  get capabilities(): Record<string, unknown> {
    return {
      apiVersion: "v1",
      writable: this.control.writable,
      taskCreation: this.control.writable && Boolean(this.taskCreator),
      deliveries: ["auto", "start", "steer", "queue"],
      queue: Boolean(this.control.setQueuedFollowUps),
      modelSettings: Boolean(this.control.updateThreadSettings && this.catalog?.listModels),
      diff: true,
      attachments: {
        enabled: true,
        kinds: ["image"],
        queued: false,
      },
    };
  }

  ingestIpcFrame(frame: IpcFrame): void {
    if (frame.type !== "broadcast") return;
    if (frame.method === "thread-queued-followups-changed") {
      if (frame.version !== 1) {
        this.store.appendEvent("protocol.unverified", {
          method: frame.method,
          expectedVersion: 1,
          receivedVersion: frame.version ?? null,
        });
      }
      const params = asRecord(frame.params);
      const threadId = readString(params?.conversationId);
      const rawMessages = params?.messages;
      const messages = Array.isArray(rawMessages)
        ? rawMessages.filter(isRecord)
        : null;
      if (
        !threadId ||
        !messages ||
        !Array.isArray(rawMessages) ||
        messages.length !== rawMessages.length
      ) {
        throw new Error("invalid-queue-change");
      }
      this.store.applyQueueSnapshot(threadId, messages);
      return;
    }
    if (frame.method !== "thread-stream-state-changed") return;
    if (frame.version !== 11) {
      this.store.appendEvent("protocol.unverified", {
        method: frame.method,
        expectedVersion: 11,
        receivedVersion: frame.version ?? null,
      });
    }
    const params = asRecord(frame.params);
    const threadId = readString(params?.conversationId);
    const change = parseStreamChange(params?.change);
    if (!threadId || !change) throw new Error("invalid-stream-change");
    try {
      this.store.applyStreamChange(threadId, change);
    } catch (error) {
      if (error instanceof Error && error.message === "stream-revision-gap") {
        this.store.appendEvent("task.resync_required", {}, threadId);
        void this.control.loadHistory(threadId).catch((loadError) => {
          this.store.appendEvent(
            "task.owner_unavailable",
            { error: errorMessage(loadError) },
            threadId,
          );
        });
      }
      throw error;
    }
  }

  async listTasks(limit = 100): Promise<TaskSummary[]> {
    return (await this.listTaskPage({ limit })).tasks;
  }

  async listTaskPage(query: TaskListQuery = {}): Promise<TaskPage> {
    const approvals = this.store.listApprovals();
    const searchTerm = query.searchTerm?.trim().toLocaleLowerCase() ?? "";
    const live = (query.archived ? [] : this.store.listThreads())
      .map((thread) => {
        const metadata = presentThreadMetadata(thread.state);
        return {
          threadId: thread.threadId,
          title: readString(thread.state.title) ?? "Untitled task",
          status: threadStatus(thread),
          revision: thread.revision,
          ownerAvailable: true,
          pendingApprovals: approvals.filter(
            (approval) => approval.threadId === thread.threadId,
          ).length,
          ...metadata,
        } satisfies TaskSummary;
      })
      .filter((task) => matchesTaskSearch(task, searchTerm));
    if (!this.catalog) return { tasks: live, nextCursor: null };
    const byId = new Map<string, TaskSummary>(
      live.map((task) => [task.threadId, task]),
    );
    const page = this.catalog.listThreadPage
      ? await this.catalog.listThreadPage(query)
      : {
          data: await this.catalog.listThreads(query.limit ?? 100),
          nextCursor: null,
        };
    for (const raw of page.data) {
      const threadId = readString(raw.id);
      if (!threadId || byId.has(threadId)) continue;
      const metadata = presentThreadMetadata(raw);
      const task: TaskSummary = {
        threadId,
        title: readString(raw.name) ?? readString(raw.preview) ?? "Untitled task",
        status: readString(raw.status) ?? "idle",
        revision: 0,
        pendingApprovals: 0,
        ownerAvailable: false,
        ...metadata,
      };
      byId.set(threadId, task);
    }
    return { tasks: [...byId.values()], nextCursor: page.nextCursor };
  }

  async listModels(): Promise<Array<Record<string, unknown>>> {
    if (!this.catalog?.listModels) throw new Error("model-list-unavailable");
    return this.catalog.listModels();
  }

  async createTask(input: CreateTaskInput): Promise<CreateTaskResult> {
    if (!this.control.writable || !this.taskCreator) {
      throw new Error("task-creation-unavailable");
    }
    if (!this.control.updateThreadSettings) {
      throw new Error("thread-settings-control-unavailable");
    }
    const cwd = input.cwd.trim();
    const prompt = input.prompt.trim();
    const model = input.model.trim();
    const effort = input.reasoningEffort.trim();
    if (!cwd) throw new Error("task-cwd-required");
    if (!prompt || prompt.length > 100_000 || prompt.includes("\u0000")) {
      throw new Error("invalid-task-prompt");
    }
    if (!model || !effort) throw new Error("invalid-thread-settings");
    const settings = { model, effort };
    assertSupportedSettings(await this.listModels(), settings);

    const materialized = await this.taskCreator.materialize({
      cwd,
      model,
      effort,
    });
    try {
      await this.waitForDesktopOwner(materialized.threadId);
    } catch (error) {
      return this.createdTaskPartial(materialized.threadId, "owner", error);
    }
    try {
      await this.control.updateThreadSettings(materialized.threadId, settings);
    } catch (error) {
      return this.createdTaskPartial(materialized.threadId, "settings", error);
    }
    try {
      await this.control.startTurn(materialized.threadId, prompt, {
        clientUserMessageId: randomUUID(),
      });
    } catch (error) {
      return this.createdTaskPartial(materialized.threadId, "prompt", error);
    }
    const result: CreateTaskResult = {
      threadId: materialized.threadId,
      promptAccepted: true,
      stage: "complete",
    };
    this.store.appendEvent(
      "task.created",
      { promptAccepted: true },
      materialized.threadId,
    );
    return result;
  }

  async follow(threadId: string): Promise<void> {
    await this.control.loadHistory(threadId);
  }

  async getTaskDetail(threadId: string): Promise<TaskDetail> {
    const live = this.store.getThread(threadId);
    if (live) return presentThread(live);
    const history = await this.catalog?.readThread?.(threadId);
    if (!history) throw new Error("task-detail-not-found");
    return presentThread({ threadId, revision: 0, state: history });
  }

  async getTaskDiff(threadId: string): Promise<TaskDiff> {
    const live = this.store.getThread(threadId);
    if (live) return presentThreadDiff(live);
    const history = await this.catalog?.readThread?.(threadId);
    if (!history) throw new Error("task-diff-not-found");
    return presentThreadDiff({ threadId, revision: 0, state: history });
  }

  getQueue(threadId: string): ThreadQueue {
    const queue = this.store.getQueue(threadId);
    if (!queue) throw new Error("queue-state-unavailable");
    return queue;
  }

  async sendMessage(
    threadId: string,
    text: string,
    options: SendMessageOptions = {},
  ): Promise<SendMessageResult> {
    const trimmed = text.trim();
    const attachments = options.attachments ?? [];
    if (!trimmed && attachments.length === 0) throw new Error("message-empty");
    const thread = this.store.getThread(threadId);
    const isActive = thread ? threadStatus(thread) === "active" : false;
    const metadata = thread ? presentThreadMetadata(thread.state) : {};
    const requested = options.delivery ?? "auto";
    const delivery: Exclude<MessageDelivery, "auto"> = requested === "auto"
      ? isActive ? "steer" : "start"
      : requested;
    const clientUserMessageId = randomUUID();

    if (delivery === "start") {
      if (isActive) throw new Error("turn-already-active");
      await this.control.startTurn(threadId, trimmed, {
        attachments,
        clientUserMessageId,
      });
    } else if (delivery === "steer") {
      if (!thread || !isActive) throw new Error("turn-not-active");
      if (requested === "steer") {
        if (!options.expectedTurnId) throw new Error("expected-turn-id-required");
        if (!metadata.activeTurnId || metadata.activeTurnId !== options.expectedTurnId) {
          throw new Error("stale-turn-id");
        }
      }
      if (!metadata.cwd) throw new Error("thread-cwd-required");
      await this.control.steer(threadId, trimmed, {
        attachments,
        clientUserMessageId,
        cwd: metadata.cwd,
        serviceTier: metadata.settings?.serviceTier ?? null,
      });
    } else {
      if (!this.control.setQueuedFollowUps) throw new Error("queue-control-unavailable");
      if (!thread || !isActive) throw new Error("turn-not-active");
      if (attachments.length > 0) throw new Error("queued-attachments-unsupported");
      if (!options.expectedQueueHash) throw new Error("expected-queue-hash-required");
      if (!metadata.cwd) throw new Error("thread-cwd-required");
      const queue = this.store.assertQueueHash(threadId, options.expectedQueueHash);
      const message = queuedMessage(trimmed, metadata.cwd);
      const messages = [...queue.messages, message];
      await this.control.setQueuedFollowUps(threadId, messages);
      this.store.appendEvent(
        "task.message_queued",
        { messageId: message.id, previousHash: queue.hash },
        threadId,
      );
      return {
        delivery,
        queuedMessageId: message.id,
        queueHash: hashQueue(messages),
      };
    }
    this.store.appendEvent(
      "task.message_sent",
      { delivery, clientUserMessageId },
      threadId,
    );
    return { delivery, clientUserMessageId };
  }

  async cancelQueuedMessage(
    threadId: string,
    messageId: string,
    expectedQueueHash: string,
  ): Promise<{ queueHash: string }> {
    if (!this.control.setQueuedFollowUps) throw new Error("queue-control-unavailable");
    const messages = this.store.queueWithoutMessage(
      threadId,
      messageId,
      expectedQueueHash,
    );
    await this.control.setQueuedFollowUps(threadId, messages);
    this.store.appendEvent(
      "task.queued_message_cancelled",
      { messageId, previousHash: expectedQueueHash },
      threadId,
    );
    return { queueHash: hashQueue(messages) };
  }

  async updateThreadSettings(
    threadId: string,
    settings: ThreadSettingsUpdate,
  ): Promise<void> {
    if (!this.control.updateThreadSettings) {
      throw new Error("thread-settings-control-unavailable");
    }
    if (!this.store.getThread(threadId)) throw new Error("task-owner-unavailable");
    const models = await this.listModels();
    assertSupportedSettings(models, settings);
    await this.control.updateThreadSettings(threadId, settings);
    this.store.appendEvent(
      "task.settings_update_requested",
      { model: settings.model, effort: settings.effort },
      threadId,
    );
  }

  async requestPush(threadId: string): Promise<void> {
    await this.sendMessage(threadId, PUSH_REQUEST_PROMPT);
    this.store.appendEvent("git.push_requested", {}, threadId);
  }

  async interrupt(threadId: string, expectedTurnId?: string): Promise<void> {
    const thread = this.store.getThread(threadId);
    if (thread && expectedTurnId) {
      const activeTurnId = presentThreadMetadata(thread.state).activeTurnId;
      if (!activeTurnId || activeTurnId !== expectedTurnId) {
        throw new Error("stale-turn-id");
      }
    }
    await this.control.interrupt(threadId, expectedTurnId);
    this.store.appendEvent("task.interrupt_requested", {}, threadId);
  }

  async respondToApproval(
    requestId: string,
    decision: ApprovalDecision,
    claimant: string,
  ): Promise<void> {
    const approval = this.store.claimApproval(requestId, claimant);
    try {
      const kind = approvalKind(approval.method);
      if (!kind) throw new Error("unsupported-approval-kind");
      await this.control.respondToApproval(
        approval.threadId,
        approval.requestId,
        kind,
        decision,
      );
      this.store.resolveApproval(requestId);
    } catch (error) {
      this.store.releaseApproval(requestId, claimant);
      throw error;
    }
  }

  async respondToUserInput(
    requestId: string,
    response: Record<string, unknown>,
    claimant: string,
  ): Promise<void> {
    const request = this.store.claimApproval(requestId, claimant);
    try {
      if (request.method !== "item/tool/requestUserInput") {
        throw new Error("request-is-not-user-input");
      }
      await this.control.respondToUserInput(request.threadId, requestId, response);
      this.store.resolveApproval(requestId);
    } catch (error) {
      this.store.releaseApproval(requestId, claimant);
      throw error;
    }
  }

  private async waitForDesktopOwner(threadId: string): Promise<void> {
    const attempts = Math.max(
      1,
      Math.ceil(this.ownerHandoffTimeoutMs / this.ownerHandoffPollIntervalMs),
    );
    let lastError: unknown = new Error("desktop-owner-unavailable");
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        await this.control.loadHistory(threadId);
        return;
      } catch (error) {
        lastError = error;
        if (!isRetryableOwnerError(error)) throw error;
      }
      if (attempt + 1 < attempts) {
        await this.sleep(this.ownerHandoffPollIntervalMs);
      }
    }
    throw new Error(`task-owner-handoff-timeout:${errorMessage(lastError)}`);
  }

  private createdTaskPartial(
    threadId: string,
    stage: Exclude<CreateTaskResult["stage"], "complete">,
    error: unknown,
  ): CreateTaskResult {
    const result: CreateTaskResult = {
      threadId,
      promptAccepted: false,
      stage,
      error: errorMessage(error),
    };
    this.store.appendEvent(
      "task.create_partial",
      {
        promptAccepted: false,
        stage,
        error: result.error!,
      },
      threadId,
    );
    return result;
  }
}

function parseStreamChange(value: unknown): StreamChange | null {
  const change = asRecord(value);
  if (!change || typeof change.revision !== "number") return null;
  if (change.type === "snapshot") {
    const state = asRecord(change.conversationState);
    return state
      ? { type: "snapshot", revision: change.revision, conversationState: state }
      : null;
  }
  if (
    change.type === "patches" &&
    typeof change.baseRevision === "number" &&
    Array.isArray(change.patches)
  ) {
    const patches: StreamChange extends never ? never : Array<{
      op: "add" | "replace" | "remove";
      path: Array<string | number>;
      value?: unknown;
    }> = [];
    for (const raw of change.patches) {
      const patch = asRecord(raw);
      if (
        !patch ||
        (patch.op !== "add" && patch.op !== "replace" && patch.op !== "remove") ||
        !Array.isArray(patch.path) ||
        !patch.path.every((part) => typeof part === "string" || typeof part === "number")
      ) {
        return null;
      }
      patches.push({
        op: patch.op,
        path: patch.path as Array<string | number>,
        ...(patch.op === "remove" ? {} : { value: patch.value }),
      });
    }
    return {
      type: "patches",
      baseRevision: change.baseRevision,
      revision: change.revision,
      patches,
    };
  }
  return null;
}

function threadStatus(thread: ThreadStream): string {
  const runtime = asRecord(thread.state.threadRuntimeStatus);
  if (runtime?.type === "active") return "active";
  const turns = Array.isArray(thread.state.turns) ? thread.state.turns : [];
  const lastTurn = asRecord(turns.at(-1));
  if (lastTurn?.status === "inProgress") return "active";
  if (lastTurn?.status === "failed") return "failed";
  return "idle";
}

function approvalKind(method: string): ApprovalKind | null {
  if (method === "item/commandExecution/requestApproval") return "command";
  if (method === "item/fileChange/requestApproval") return "file";
  if (method === "item/permissions/requestApproval") return "permission";
  return null;
}

function queuedMessage(text: string, cwd: string): QueuedFollowUpMessage {
  const id = randomUUID();
  return {
    id,
    text,
    context: {
      prompt: text,
      turnTrigger: "user",
      addedFiles: [],
      fileAttachments: [],
      ideContext: null,
      imageAttachments: [],
      workspaceRoots: [cwd],
    },
    cwd,
    createdAt: Date.now(),
  };
}

function matchesTaskSearch(task: TaskSummary, searchTerm: string): boolean {
  if (!searchTerm) return true;
  return [task.title, task.cwd, task.gitInfo?.branch]
    .filter((value): value is string => typeof value === "string")
    .some((value) => value.toLocaleLowerCase().includes(searchTerm));
}

function assertSupportedSettings(
  models: Array<Record<string, unknown>>,
  settings: ThreadSettingsUpdate,
): void {
  const model = models.find((candidate) => {
    return readString(candidate.id) === settings.model || readString(candidate.model) === settings.model;
  });
  if (!model) throw new Error("unsupported-model");
  const rawEfforts = Array.isArray(model.supportedReasoningEfforts)
    ? model.supportedReasoningEfforts
    : [];
  const efforts = rawEfforts
    .map((value) => {
      if (typeof value === "string") return value;
      const record = asRecord(value);
      return (
        readString(record?.effort) ??
        readString(record?.reasoningEffort) ??
        readString(record?.value)
      );
    })
    .filter((value): value is string => Boolean(value));
  if (efforts.length > 0 && !efforts.includes(settings.effort)) {
    throw new Error("unsupported-reasoning-effort");
  }
}

function isRetryableOwnerError(error: unknown): boolean {
  const message = errorMessage(error).toLowerCase();
  return ["owner", "unavailable", "timeout", "disconnect", "pipe", "not-connected"]
    .some((token) => message.includes(token));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return asRecord(value) !== null;
}

function readString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
