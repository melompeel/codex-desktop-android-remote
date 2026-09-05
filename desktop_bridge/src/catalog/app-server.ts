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

export class AppServerCatalog implements TaskCatalogPort {
  private process: ChildProcessWithoutNullStreams | null = null;
  private incoming = "";
  private readonly pending = new Map<string, Pending>();
  private starting: Promise<void> | null = null;

  constructor(
    private readonly executable: string,
    private readonly timeoutMs = 10_000,
  ) {}

  async start(): Promise<void> {
    if (this.process) return;
    if (this.starting) return this.starting;
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

  async listModels(): Promise<Array<Record<string, unknown>>> {
    const result = await this.readRequest("model/list", {});
    return Array.isArray(result.data)
      ? result.data.filter(isRecord).map(normalizeModelDescriptor).filter(isRecord)
      : [];
  }

  dispose(): void {
    this.rejectAll(new Error("app-server-catalog-disposed"));
    this.process?.kill();
    this.process = null;
  }

  private async spawnAndInitialize(): Promise<void> {
    const child = spawn(this.executable, ["app-server", "--listen", "stdio://"], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.process = child;
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => this.handleData(chunk));
    child.stderr.on("data", () => undefined);
    child.on("exit", () => {
      if (this.process === child) this.process = null;
      this.rejectAll(new Error("app-server-catalog-exited"));
    });
    await this.sendRequest("initialize", {
      clientInfo: {
        name: "codex-desktop-android-remote",
        title: "Codex Desktop Android Remote",
        version: "0.1.0",
      },
    });
    child.stdin.write(`${JSON.stringify({ method: "initialized", params: {} })}\n`);
  }

  private async readRequest(
    method: "thread/list" | "thread/read" | "model/list",
    params: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    await this.start();
    return this.sendRequest(method, params);
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
        reject(new Error(`app-server-timeout:${method}`));
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    });
  }

  private handleData(chunk: string): void {
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

  private rejectAll(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }
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
