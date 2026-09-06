import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import type { ChildProcessWithoutNullStreams } from "node:child_process";

import { describe, expect, it } from "vitest";

import { AppServerCatalog } from "../src/catalog/app-server.js";

type RequestMessage = {
  id?: string;
  method?: string;
  params?: Record<string, unknown>;
};

class FakeAppServer extends EventEmitter {
  readonly stdin = new PassThrough();
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  killed = false;
  private incoming = "";

  constructor(
    private readonly respond: (
      request: RequestMessage,
      server: FakeAppServer,
    ) => void,
  ) {
    super();
    this.stdin.setEncoding("utf8");
    this.stdin.on("data", (chunk: string) => this.handleInput(chunk));
  }

  asChild(): ChildProcessWithoutNullStreams {
    return this as unknown as ChildProcessWithoutNullStreams;
  }

  result(id: string, result: Record<string, unknown>): void {
    this.stdout.write(`${JSON.stringify({ id, result })}\n`);
  }

  error(id: string, message: string): void {
    this.stdout.write(`${JSON.stringify({ id, error: { message } })}\n`);
  }

  kill(): boolean {
    if (this.killed) return false;
    this.killed = true;
    queueMicrotask(() => this.emit("exit", 0, null));
    return true;
  }

  private handleInput(chunk: string): void {
    this.incoming += chunk;
    for (;;) {
      const newline = this.incoming.indexOf("\n");
      if (newline < 0) return;
      const line = this.incoming.slice(0, newline);
      this.incoming = this.incoming.slice(newline + 1);
      const request = JSON.parse(line) as RequestMessage;
      if (request.id) this.respond(request, this);
    }
  }
}

describe("AppServerCatalog", () => {
  it("restarts after initialization fails", async () => {
    const first = new FakeAppServer((request, server) => {
      if (request.method === "initialize") server.error(request.id!, "initialize-failed");
    });
    const second = modelServer([{ id: "gpt-6-astra", displayName: "GPT-6 Astra" }]);
    const children = [first, second];
    let spawns = 0;
    const catalog = new AppServerCatalog("codex", 20, () => {
      spawns += 1;
      return children.shift()!.asChild();
    });

    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-6-astra" }]);
    expect(spawns).toBe(2);
    expect(first.killed).toBe(true);
    catalog.dispose();
  });

  it("restarts and retries after a model request times out", async () => {
    const first = new FakeAppServer((request, server) => {
      if (request.method === "initialize") server.result(request.id!, {});
    });
    const second = modelServer([{ id: "gpt-6-astra", displayName: "GPT-6 Astra" }]);
    const children = [first, second];
    let spawns = 0;
    const catalog = new AppServerCatalog("codex", 20, () => {
      spawns += 1;
      return children.shift()!.asChild();
    });

    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-6-astra" }]);
    expect(spawns).toBe(2);
    expect(first.killed).toBe(true);
    catalog.dispose();
  });

  it("reads every model page so newly added models are not truncated", async () => {
    const requestedCursors: Array<string | null> = [];
    const server = new FakeAppServer((request, child) => {
      if (request.method === "initialize") {
        child.result(request.id!, {});
        return;
      }
      if (request.method !== "model/list") return;
      const cursor = typeof request.params?.cursor === "string"
        ? request.params.cursor
        : null;
      requestedCursors.push(cursor);
      if (!cursor) {
        child.result(request.id!, {
          data: [{ id: "gpt-5.6-sol", displayName: "GPT-5.6 Sol" }],
          nextCursor: "models-page-2",
        });
      } else {
        child.result(request.id!, {
          data: [{ id: "gpt-6-astra", displayName: "GPT-6 Astra" }],
          nextCursor: null,
        });
      }
    });
    const catalog = new AppServerCatalog("codex", 20, () => server.asChild());

    await expect(catalog.listModels()).resolves.toMatchObject([
      { id: "gpt-5.6-sol" },
      { id: "gpt-6-astra" },
    ]);
    expect(requestedCursors).toEqual([null, "models-page-2"]);
    catalog.dispose();
  });

  it("caches rapid model refreshes but rotates an aged process and executable", async () => {
    let now = 1_000;
    let executable = "codex-old.exe";
    let oldModelRequests = 0;
    const first = new FakeAppServer((request, server) => {
      if (request.method === "initialize") server.result(request.id!, {});
      if (request.method === "model/list") {
        oldModelRequests += 1;
        server.result(request.id!, {
          data: [{ id: "gpt-5.6-sol", displayName: "GPT-5.6 Sol" }],
          nextCursor: null,
        });
      }
    });
    const second = modelServer([{ id: "gpt-6-astra", displayName: "GPT-6 Astra" }]);
    const children = [first, second];
    const spawnedExecutables: string[] = [];
    const catalog = new AppServerCatalog(
      () => executable,
      20,
      (resolvedExecutable) => {
        spawnedExecutables.push(resolvedExecutable);
        return children.shift()!.asChild();
      },
      () => now,
    );

    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-5.6-sol" }]);
    now += 1_000;
    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-5.6-sol" }]);
    expect(oldModelRequests).toBe(1);

    executable = "codex-new.exe";
    now += 5 * 60_000 + 1;
    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-6-astra" }]);
    expect(first.killed).toBe(true);
    expect(spawnedExecutables).toEqual(["codex-old.exe", "codex-new.exe"]);
    catalog.dispose();
  });

  it("supports an explicit model refresh without waiting for process expiry", async () => {
    const first = modelServer([{ id: "gpt-5.6-sol" }]);
    const second = modelServer([{ id: "gpt-6-astra" }]);
    const children = [first, second];
    const catalog = new AppServerCatalog(
      "codex.exe",
      20,
      () => children.shift()!.asChild(),
    );

    await expect(catalog.listModels()).resolves.toMatchObject([{ id: "gpt-5.6-sol" }]);
    await expect(catalog.listModels(true)).resolves.toMatchObject([{ id: "gpt-6-astra" }]);
    expect(first.killed).toBe(true);
    catalog.dispose();
  });
});

function modelServer(models: Array<Record<string, unknown>>): FakeAppServer {
  return new FakeAppServer((request, server) => {
    if (request.method === "initialize") server.result(request.id!, {});
    if (request.method === "model/list") {
      server.result(request.id!, { data: models, nextCursor: null });
    }
  });
}
