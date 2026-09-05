import { createHash } from "node:crypto";

export type StreamSnapshot = {
  type: "snapshot";
  revision: number;
  conversationState: Record<string, unknown>;
};

export type StreamPatches = {
  type: "patches";
  baseRevision: number;
  revision: number;
  patches: Array<{
    op: "add" | "replace" | "remove";
    path: Array<string | number>;
    value?: unknown;
  }>;
};

export type StreamChange = StreamSnapshot | StreamPatches;

export type ThreadStream = {
  threadId: string;
  revision: number;
  state: Record<string, unknown>;
};

export type PendingApproval = {
  requestId: string;
  threadId: string;
  method: string;
  expiresAt: number;
  payload: Record<string, unknown>;
  claimedBy?: string;
};

export type BridgeEvent = {
  sequence: number;
  type: string;
  threadId?: string;
  createdAt: number;
  payload: Record<string, unknown>;
};

export type QueuedFollowUpMessage = Record<string, unknown> & {
  id: string;
  text: string;
};

export type ThreadQueue = {
  threadId: string;
  hash: string;
  messages: QueuedFollowUpMessage[];
};

export type IdempotencyClaim =
  | { replayed: false }
  | { replayed: true; result: unknown };

type IdempotencyEntry = {
  status: "pending" | "completed";
  expiresAt: number;
  result?: unknown;
};

export class BridgeStore {
  private readonly threads = new Map<string, ThreadStream>();
  private readonly queues = new Map<string, ThreadQueue>();
  private readonly approvals = new Map<string, PendingApproval>();
  private readonly idempotency = new Map<string, IdempotencyEntry>();
  private readonly events: BridgeEvent[] = [];
  private readonly listeners = new Set<(event: BridgeEvent) => void>();
  private nextSequence = 1;

  constructor(
    private readonly now: () => number = Date.now,
    private readonly eventLimit = 1_000,
  ) {}

  applyStreamChange(threadId: string, change: StreamChange): ThreadStream {
    let thread: ThreadStream;
    if (change.type === "snapshot") {
      thread = {
        threadId,
        revision: change.revision,
        state: structuredClone(change.conversationState),
      };
    } else {
      const current = this.threads.get(threadId);
      if (!current || current.revision !== change.baseRevision) {
        throw new Error("stream-revision-gap");
      }
      const state = structuredClone(current.state);
      for (const patch of change.patches) applyPatch(state, patch);
      thread = { threadId, revision: change.revision, state };
    }
    this.threads.set(threadId, thread);
    this.syncApprovals(threadId, thread.state);
    this.appendEvent("task.updated", { revision: thread.revision }, threadId);
    return structuredClone(thread);
  }

  getThread(threadId: string): ThreadStream | null {
    const thread = this.threads.get(threadId);
    return thread ? structuredClone(thread) : null;
  }

  listThreads(): ThreadStream[] {
    return [...this.threads.values()].map((thread) => structuredClone(thread));
  }

  applyQueueSnapshot(
    threadId: string,
    messages: Array<Record<string, unknown>>,
  ): ThreadQueue {
    if (!threadId) throw new Error("queue-thread-id-required");
    const normalized = messages.map(normalizeQueuedMessage);
    const queue: ThreadQueue = {
      threadId,
      hash: hashQueue(normalized),
      messages: structuredClone(normalized),
    };
    this.queues.set(threadId, queue);
    this.appendEvent(
      "task.queue_updated",
      { hash: queue.hash, count: queue.messages.length },
      threadId,
    );
    return structuredClone(queue);
  }

  getQueue(threadId: string): ThreadQueue | null {
    const queue = this.queues.get(threadId);
    return queue ? structuredClone(queue) : null;
  }

  assertQueueHash(threadId: string, expectedHash: string): ThreadQueue {
    const queue = this.queues.get(threadId);
    if (!queue) throw new Error("queue-state-unavailable");
    if (!expectedHash || queue.hash !== expectedHash) {
      throw new Error("queue-hash-conflict");
    }
    return structuredClone(queue);
  }

  queueWithoutMessage(
    threadId: string,
    messageId: string,
    expectedHash: string,
  ): QueuedFollowUpMessage[] {
    const queue = this.assertQueueHash(threadId, expectedHash);
    if (!queue.messages.some((message) => message.id === messageId)) {
      throw new Error("queued-message-not-found");
    }
    return queue.messages.filter((message) => message.id !== messageId);
  }

  beginIdempotent(
    scope: string,
    idempotencyKey: string,
    ttlMs = 10 * 60_000,
  ): IdempotencyClaim {
    validateIdempotencyPart(scope);
    validateIdempotencyPart(idempotencyKey);
    if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0) {
      throw new Error("invalid-idempotency-ttl");
    }
    this.pruneIdempotency();
    const key = idempotencyMapKey(scope, idempotencyKey);
    const current = this.idempotency.get(key);
    if (current?.status === "pending") throw new Error("idempotency-in-progress");
    if (current?.status === "completed") {
      return { replayed: true, result: structuredClone(current.result) };
    }
    this.idempotency.set(key, {
      status: "pending",
      expiresAt: this.now() + ttlMs,
    });
    return { replayed: false };
  }

  completeIdempotent(
    scope: string,
    idempotencyKey: string,
    result: unknown,
  ): void {
    const key = idempotencyMapKey(scope, idempotencyKey);
    const current = this.idempotency.get(key);
    if (!current || current.expiresAt <= this.now()) {
      this.idempotency.delete(key);
      throw new Error("idempotency-claim-not-found");
    }
    if (current.status === "completed") return;
    current.status = "completed";
    current.result = structuredClone(result);
  }

  releaseIdempotent(scope: string, idempotencyKey: string): void {
    const key = idempotencyMapKey(scope, idempotencyKey);
    const current = this.idempotency.get(key);
    if (current?.status === "pending") this.idempotency.delete(key);
  }

  registerApproval(approval: PendingApproval): void {
    const current = this.approvals.get(approval.requestId);
    if (current?.claimedBy) return;
    this.approvals.set(approval.requestId, structuredClone(approval));
    this.appendEvent("approval.requested", sanitizeApproval(approval), approval.threadId);
  }

  listApprovals(): PendingApproval[] {
    const now = this.now();
    return [...this.approvals.values()]
      .filter((item) => !item.claimedBy && item.expiresAt > now)
      .map((item) => structuredClone(item));
  }

  getApproval(requestId: string): PendingApproval | null {
    const approval = this.approvals.get(requestId);
    return approval ? structuredClone(approval) : null;
  }

  claimApproval(requestId: string, claimant: string): PendingApproval {
    const approval = this.approvals.get(requestId);
    if (!approval) throw new Error("approval-not-found");
    if (approval.claimedBy) throw new Error("approval-already-resolved");
    if (approval.expiresAt <= this.now()) throw new Error("approval-expired");
    approval.claimedBy = claimant;
    this.appendEvent(
      "approval.claimed",
      { requestId, claimant },
      approval.threadId,
    );
    return structuredClone(approval);
  }

  releaseApproval(requestId: string, claimant: string): void {
    const approval = this.approvals.get(requestId);
    if (approval?.claimedBy === claimant) delete approval.claimedBy;
  }

  resolveApproval(requestId: string): void {
    const approval = this.approvals.get(requestId);
    if (!approval) return;
    this.approvals.delete(requestId);
    this.appendEvent("approval.resolved", { requestId }, approval.threadId);
  }

  appendEvent(
    type: string,
    payload: Record<string, unknown>,
    threadId?: string,
  ): BridgeEvent {
    const event: BridgeEvent = {
      sequence: this.nextSequence++,
      type,
      createdAt: this.now(),
      payload,
      ...(threadId ? { threadId } : {}),
    };
    this.events.push(event);
    if (this.events.length > this.eventLimit) this.events.shift();
    for (const listener of this.listeners) listener(structuredClone(event));
    return structuredClone(event);
  }

  eventsAfter(cursor: number): BridgeEvent[] {
    return this.events
      .filter((event) => event.sequence > cursor)
      .map((event) => structuredClone(event));
  }

  subscribe(listener: (event: BridgeEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private syncApprovals(threadId: string, state: Record<string, unknown>): void {
    const requests = Array.isArray(state.requests) ? state.requests : [];
    const liveIds = new Set<string>();
    for (const entry of requests) {
      if (!isRecord(entry)) continue;
      const requestId =
        typeof entry.id === "string" || typeof entry.id === "number"
          ? String(entry.id)
          : null;
      const method = typeof entry.method === "string" ? entry.method : null;
      if (!requestId || !method || !isApprovalMethod(method)) continue;
      liveIds.add(requestId);
      this.registerApproval({
        requestId,
        threadId,
        method,
        expiresAt: this.now() + 5 * 60_000,
        payload: redactPayload(entry),
      });
    }
    for (const [requestId, approval] of this.approvals) {
      if (approval.threadId === threadId && !liveIds.has(requestId)) {
        this.resolveApproval(requestId);
      }
    }
  }

  private pruneIdempotency(): void {
    const now = this.now();
    for (const [key, entry] of this.idempotency) {
      if (entry.expiresAt <= now) this.idempotency.delete(key);
    }
  }
}

export function hashQueue(messages: QueuedFollowUpMessage[]): string {
  return createHash("sha256").update(stableJson(messages)).digest("hex");
}

function applyPatch(
  root: Record<string, unknown>,
  patch: StreamPatches["patches"][number],
): void {
  if (patch.path.length === 0) {
    if (patch.op !== "replace" || !isRecord(patch.value)) {
      throw new Error("unsupported-stream-patch");
    }
    for (const key of Object.keys(root)) delete root[key];
    Object.assign(root, structuredClone(patch.value));
    return;
  }
  let container: unknown = root;
  for (const segment of patch.path.slice(0, -1)) {
    if (Array.isArray(container)) {
      const index = toIndex(segment);
      if (index === null || index >= container.length) throw new Error("invalid-stream-patch");
      container = container[index];
    } else if (isRecord(container)) {
      container = container[String(segment)];
    } else {
      throw new Error("invalid-stream-patch");
    }
  }
  const leaf = patch.path.at(-1);
  if (leaf === undefined) throw new Error("invalid-stream-patch");
  if (Array.isArray(container)) {
    const index = toIndex(leaf);
    if (index === null) throw new Error("invalid-stream-patch");
    if (patch.op === "add") container.splice(index, 0, structuredClone(patch.value));
    else if (patch.op === "replace") container[index] = structuredClone(patch.value);
    else container.splice(index, 1);
    return;
  }
  if (!isRecord(container)) throw new Error("invalid-stream-patch");
  const key = String(leaf);
  if (patch.op === "remove") delete container[key];
  else container[key] = structuredClone(patch.value);
}

function toIndex(value: string | number): number | null {
  const index = typeof value === "number" ? value : Number(value);
  return Number.isSafeInteger(index) && index >= 0 ? index : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isApprovalMethod(method: string): boolean {
  return new Set([
    "item/commandExecution/requestApproval",
    "item/fileChange/requestApproval",
    "item/permissions/requestApproval",
    "item/tool/requestUserInput",
  ]).has(method);
}

function redactPayload(value: Record<string, unknown>): Record<string, unknown> {
  const allowed = ["id", "method", "params", "reason", "command", "cwd"];
  return Object.fromEntries(
    allowed.filter((key) => key in value).map((key) => [key, value[key]]),
  );
}

function sanitizeApproval(approval: PendingApproval): Record<string, unknown> {
  return {
    requestId: approval.requestId,
    method: approval.method,
    expiresAt: approval.expiresAt,
    payload: approval.payload,
  };
}

function normalizeQueuedMessage(
  value: Record<string, unknown>,
): QueuedFollowUpMessage {
  if (!isRecord(value)) throw new Error("invalid-queued-message");
  if (typeof value.id !== "string" || value.id.length === 0) {
    throw new Error("invalid-queued-message");
  }
  if (typeof value.text !== "string") throw new Error("invalid-queued-message");
  return structuredClone(value) as QueuedFollowUpMessage;
}

function stableJson(value: unknown): string {
  if (value === null || typeof value === "string" || typeof value === "boolean") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("invalid-queue-value");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableJson).join(",")}]`;
  }
  if (isRecord(value)) {
    return `{${Object.keys(value)
      .filter((key) => value[key] !== undefined)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`)
      .join(",")}}`;
  }
  throw new Error("invalid-queue-value");
}

function validateIdempotencyPart(value: string): void {
  if (value.length === 0 || value.length > 200 || /[\u0000-\u001F\u007F]/.test(value)) {
    throw new Error("invalid-idempotency-key");
  }
}

function idempotencyMapKey(scope: string, idempotencyKey: string): string {
  validateIdempotencyPart(scope);
  validateIdempotencyPart(idempotencyKey);
  return JSON.stringify([scope, idempotencyKey]);
}
