import { createServer, type Server, type Socket } from "node:net";

import { afterEach, describe, expect, it } from "vitest";

import { CodexIpcClient } from "../src/ipc/client.js";
import { encodeFrame, FrameDecoder } from "../src/ipc/framing.js";
import type { IpcFrame } from "../src/ipc/types.js";

describe("CodexIpcClient", () => {
  let server: Server | null = null;
  let client: CodexIpcClient | null = null;

  afterEach(async () => {
    client?.dispose();
    await new Promise<void>((resolve) => server?.close(() => resolve()) ?? resolve());
  });

  it("initializes and correlates requests over a real split-frame socket", async () => {
    server = createServer((socket) => serveRouter(socket));
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("missing-test-port");

    client = new CodexIpcClient({
      endpoint: { host: "127.0.0.1", port: address.port },
      clientType: "test-client",
      requestTimeoutMs: 1_000,
      reconnectDelayMs: 20,
    });

    await client.connect();
    const response = await client.request(
      "thread-owner-discovery",
      { hostId: "local", conversationId: "thread-1" },
      { version: 1 },
    );

    expect(client.status).toBe("connected");
    expect(response).toMatchObject({
      resultType: "success",
      handledByClientId: "desktop-owner",
    });
  });

  it("answers discovery probes with canHandle false instead of hanging the router", async () => {
    let discoveryResponse: IpcFrame | null = null;
    server = createServer((socket) => {
      const decoder = new FrameDecoder();
      socket.on("data", (chunk) => {
        for (const frame of decoder.push(chunk)) {
          if (frame.method === "initialize") {
            socket.write(
              encodeFrame({
                type: "response",
                requestId: frame.requestId,
                resultType: "success",
                method: "initialize",
                result: { clientId: "probe-client" },
              }),
            );
            socket.write(
              encodeFrame({
                type: "client-discovery-request",
                requestId: "discovery-1",
                request: {
                  type: "request",
                  method: "unknown-method",
                  version: 1,
                },
              }),
            );
          } else if (frame.type === "client-discovery-response") {
            discoveryResponse = frame;
          }
        }
      });
    });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("missing-test-port");
    client = new CodexIpcClient({
      endpoint: { host: "127.0.0.1", port: address.port },
      clientType: "test-client",
    });

    await client.connect();
    await new Promise((resolve) => setTimeout(resolve, 25));

    expect(discoveryResponse).toMatchObject({
      type: "client-discovery-response",
      requestId: "discovery-1",
      response: { canHandle: false },
    });
  });

  it("reports socket failures without EventEmitter's unhandled error crash", async () => {
    server = createServer((socket) => serveRouter(socket));
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("missing-test-port");
    client = new CodexIpcClient({
      endpoint: { host: "127.0.0.1", port: address.port },
      clientType: "test-client",
      reconnectDelayMs: 60_000,
    });
    const errors: string[] = [];
    client.onError((error) => errors.push(error.message));
    await client.connect();

    server.close();
    const socket = (client as unknown as { socket: Socket }).socket;
    socket.emit("error", new Error("synthetic-socket-error"));

    expect(errors).toContain("synthetic-socket-error");
  });

  it("reconnects when the router was unavailable on the first attempt", async () => {
    server = createServer();
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("missing-test-port");
    const port = address.port;
    await new Promise<void>((resolve) => server!.close(() => resolve()));

    client = new CodexIpcClient({
      endpoint: { host: "127.0.0.1", port },
      clientType: "test-client",
      reconnectDelayMs: 20,
      requestTimeoutMs: 500,
    });
    await expect(client.connect()).rejects.toThrow();

    server = createServer((socket) => serveRouter(socket));
    await new Promise<void>((resolve) => server!.listen(port, "127.0.0.1", resolve));
    await waitUntil(() => client?.status === "connected", 1_000);
    expect(client.status).toBe("connected");
  });

  it("reconnects a stalled pipe instead of reporting connected forever after timeouts", async () => {
    let connections = 0;
    server = createServer((socket) => {
      connections += 1;
      if (connections > 1) return serveRouter(socket);
      const decoder = new FrameDecoder();
      socket.on("data", (chunk) => {
        for (const frame of decoder.push(chunk)) {
          if (frame.method === "initialize") socket.write(encodeFrame({
            type: "response", requestId: frame.requestId, resultType: "success",
            result: { clientId: "stalled-client" },
          }));
        }
      });
    });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("missing-test-port");
    client = new CodexIpcClient({
      endpoint: { host: "127.0.0.1", port: address.port },
      clientType: "test-client", reconnectDelayMs: 10, requestTimeoutMs: 50,
    });
    await expect(client.request("thread-owner-discovery", {}, { version: 1 }))
      .rejects.toThrow("ipc-request-timeout");
    await waitUntil(() => connections >= 2 && client?.status === "connected", 1_000);
    await expect(client.request("thread-owner-discovery", {}, { version: 1 }))
      .resolves.toMatchObject({ resultType: "success" });
  });
});

async function waitUntil(predicate: () => boolean, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("condition-timeout");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function serveRouter(socket: Socket): void {
  const decoder = new FrameDecoder();
  socket.on("data", (chunk) => {
    for (const frame of decoder.push(chunk)) {
      if (frame.method === "initialize") {
        const response = encodeFrame({
          type: "response",
          requestId: frame.requestId,
          resultType: "success",
          method: "initialize",
          result: { clientId: "test-client-id" },
        });
        socket.write(response.subarray(0, 2));
        socket.write(response.subarray(2));
      } else if (frame.method === "thread-owner-discovery") {
        socket.write(
          encodeFrame({
            type: "response",
            requestId: frame.requestId,
            resultType: "success",
            method: frame.method,
            handledByClientId: "desktop-owner",
            result: { supportsUntrustedAppInput: true },
          }),
        );
      }
    }
  });
}
