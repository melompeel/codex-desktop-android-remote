import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";

import type {
  CatalogThreadPage,
  TaskCatalogPort,
  TaskListQuery,
} from "../bridge/controller.js";

type Pending = {
  resolve: (value: Record<string, unknown>) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

type AppServerProcessFactory = (
  executable: string,
  args: string[],
) => ChildProcessWithoutNullStreams;

type AppServerExecutable = string | (() => string | Promise<string>);

const MODEL_CACHE_TTL_MS = 30_000;
const MODEL_PROCESS_MAX_AGE_MS = 5 * 60_000;

export class AppServerCatalog implements TaskCatalogPort {
  private process: ChildProcessWithoutNullStreams | null = null;
  private incoming = "";
  private readonly pending = new Map<string, Pending>();
  private starting: Promise<void> | null = null;
  private processStartedAt = 0;
  private modelCache: {
    expiresAt: number;
    models: Array<Record<string, unknown>>;
  } | null = null;

  constructor(
    private readonly executable: AppServerExecutable,
    private readonly timeoutMs = 10_000,
    private readonly processFactory: AppServerProcessFactory = spawnAppServer,
    private readonly now: () => number = Date.now,
  ) {}

  async start(): Promise<void> {
    if (this.starting) return this.starting;
    if (this.process?.stdin.writable) return;
    this.starting = this.spawnAndInitialize().finally(() => {
      this.starting = null;
    });
    return this.starting;
  }

  async listThreads(limit = 100): Promise<Array<Record<string, unknown>>> {
    return (await this.listThreadPage({ limit })).data;
  }

  async listThreadPage(query: TaskListQuery): Promise<CatalogThreadPage> {
    const params: Record<string, unknown> = {
      limit: normalizeLimit(query.limit),
      archived: query.archived ?? false,
      ...(query.cursor ? { cursor: query.cursor } : {}),
      ...(query.searchTerm ? { searchTerm: query.searchTerm } : {}),
    };
    const result = await this.readRequest("thread/list", params);
    return {
      data: Array.isArray(result.data) ? result.data.filter(isRecord) : [],
      nextCursor: readString(result.nextCursor),
    };
  }

  async readThread(threadId: string): Promise<Record<string, unknown> | null> {
    const result = await this.readRequest("thread/read", {
      threadId,
      includeTurns: true,
    });
    return isRecord(result.thread) ? result.thread : null;
  }

  async hasThread(threadId: string): Promise<boolean> {
    const result = await this.readRequest("thread/read", {
      threadId,
      includeTurns: false,
    });
    return isRecord(result.thread);
  }

  async listModels(refresh = false): Promise<Array<Record<string, unknown>>> {
    const now = this.now();
    if (!refresh && this.modelCache && this.modelCache.expiresAt > now) {
      return structuredClone(this.modelCache.models);
    }
    if (
      this.process &&
      !this.starting &&
      this.pending.size === 0 &&
      (refresh || now - this.processStartedAt >= MODEL_PROCESS_MAX_AGE_MS)
    ) {
      this.retire(this.process, new Error("app-server-model-refresh"));
    }
    const models: Array<Record<string, unknown>> = [];
    const modelIds = new Set<string>();
    const cursors = new Set<string>();
    let cursor: string | null = null;
    for (;;) {
      const result = await this.readRequest("model/list", {
        limit: 100,
        ...(cursor ? { cursor } : {}),
      });
      const page = Array.isArray(result.data)
        ? result.data.filter(isRecord).map(normalizeModelDescriptor).filter(isRecord)
        : [];
      for (const model of page) {
        const id = readString(model.id);
        if (!id || modelIds.has(id)) continue;
        modelIds.add(id);
        models.push(model);
      }
      const nextCursor = readString(result.nextCursor);
      if (!nextCursor) {
        this.modelCache = {
          expiresAt: this.now() + MODEL_CACHE_TTL_MS,
          models: structuredClone(models),
        };
        return models;
      }
      if (cursors.has(nextCursor)) throw new Error("app-server-model-cursor-cycle");
      cursors.add(nextCursor);
      cursor = nextCursor;
    }
  }

  dispose(): void {
    this.modelCache = null;
    const error = new Error("app-server-catalog-disposed");
    const child = this.process;
    if (child) this.retire(child, error);
    else this.rejectAll(error);
  }

  private async spawnAndInitialize(): Promise<void> {
    const executable = typeof this.executable === "string"
      ? this.executable
      : await this.executable();
    const child = this.processFactory(
      executable,
      ["app-server", "--listen", "stdio://"],
    );
    this.process = child;
    this.processStartedAt = this.now();
    this.incoming = "";
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => this.handleData(child, chunk));
    child.stderr.on("data", () => undefined);
    child.on("error", (error) => this.retire(child, error, false));
    child.on("exit", () => {
      this.retire(child, new Error("app-server-catalog-exited"), false);
    });
    try {
      await this.sendRequest("initialize", {
        clientInfo: {
          name: "codex-desktop-android-remote",
          title: "Codex Desktop Android Remote",
          version: "0.1.0",
        },
      });
      if (this.process !== child || !child.stdin.writable) {
        throw new Error("app-server-not-running");
      }
      child.stdin.write(`${JSON.stringify({ method: "initialized", params: {} })}\n`);
    } catch (error) {
      const failure = toError(error);
      this.retire(child, failure);
      throw failure;
    }
  }

  private async readRequest(
    method: "thread/list" | "thread/read" | "model/list",
    params: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    let lastError = new Error("app-server-not-running");
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        await this.start();
      } catch (error) {
        lastError = toError(error);
        if (attempt === 0) continue;
        throw lastError;
      }
      try {
        return await this.sendRequest(method, params);
      } catch (error) {
        lastError = toError(error);
        if (attempt === 0 && isRecoverable(lastError)) continue;
        throw lastError;
      }
    }
    throw lastError;
  }

  private sendRequest(
    method: string,
    params: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    const child = this.process;
    if (!child?.stdin.writable) return Promise.reject(new Error("app-server-not-running"));
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        const error = new Error(`app-server-timeout:${method}`);
        this.retire(child, error);
        reject(error);
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try {
        child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        const failure = toError(error);
        this.retire(child, failure);
        reject(failure);
      }
    });
  }

  private handleData(child: ChildProcessWithoutNullStreams, chunk: string): void {
    if (this.process !== child) return;
    this.incoming += chunk;
    for (;;) {
      const newline = this.incoming.indexOf("\n");
      if (newline < 0) return;
      const line = this.incoming.slice(0, newline).trim();
      this.incoming = this.incoming.slice(newline + 1);
      if (!line) continue;
      let message: unknown;
      try {
        message = JSON.parse(line);
      } catch {
        continue;
      }
      if (!isRecord(message) || (typeof message.id !== "string" && typeof message.id !== "number")) continue;
      const pending = this.pending.get(String(message.id));
      if (!pending) continue;
      clearTimeout(pending.timer);
      this.pending.delete(String(message.id));
      if (isRecord(message.error)) {
        pending.reject(new Error(readString(message.error.message) ?? "app-server-error"));
      } else {
        pending.resolve(isRecord(message.result) ? message.result : {});
      }
    }
  }

  private retire(
    child: ChildProcessWithoutNullStreams,
    error: Error,
    kill = true,
  ): void {
    if (this.process !== child) return;
    this.process = null;
    this.processStartedAt = 0;
    this.incoming = "";
    this.rejectAll(error);
    if (kill && !child.killed) child.kill();
  }

  private rejectAll(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }
}

function spawnAppServer(
  executable: string,
  args: string[],
): ChildProcessWithoutNullStreams {
  return spawn(executable, args, {
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
}

function isRecoverable(error: Error): boolean {
  return error.message.startsWith("app-server-timeout:") ||
    error.message === "app-server-not-running" ||
    error.message === "app-server-catalog-exited";
}

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}

function normalizeLimit(value: number | undefined): number {
  if (value === undefined) return 100;
  if (!Number.isSafeInteger(value) || value < 1 || value > 100) {
    throw new Error("invalid-task-limit");
  }
  return value;
}

function normalizeModelDescriptor(
  value: Record<string, unknown>,
): Record<string, unknown> | null {
  const id = readString(value.id);
  if (!id) return null;
  const result: Record<string, unknown> = { id };
  for (const key of ["model", "displayName", "description", "defaultReasoningEffort"] as const) {
    const field = readString(value[key]);
    if (field) result[key] = field;
  }
  if (typeof value.isDefault === "boolean") result.isDefault = value.isDefault;
  if (Array.isArray(value.inputModalities)) {
    result.inputModalities = value.inputModalities.filter(
      (entry): entry is string => typeof entry === "string",
    );
  }
  if (Array.isArray(value.supportedReasoningEfforts)) {
    result.supportedReasoningEfforts = value.supportedReasoningEfforts
      .map((entry) => {
        const effort = isRecord(entry) ? readString(entry.reasoningEffort) : null;
        if (!effort) return null;
        const description = readString(entry.description);
        return {
          reasoningEffort: effort,
          ...(description ? { description } : {}),
        };
      })
      .filter(isRecord);
  }
  if (Array.isArray(value.serviceTiers)) {
    result.serviceTiers = structuredClone(value.serviceTiers);
  }
  return result;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function readString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}
