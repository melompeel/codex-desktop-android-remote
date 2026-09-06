import { EventEmitter } from "node:events";
import { createConnection, type Socket } from "node:net";
import { randomUUID } from "node:crypto";

import { DEFAULT_MAX_FRAME_BYTES, encodeFrame, FrameDecoder } from "./framing.js";
import type { IpcBroadcastListener, IpcFrame } from "./types.js";

export type IpcEndpoint = string | { host: string; port: number };
export type IpcConnectionStatus = "disconnected" | "connecting" | "connected";

export type CodexIpcClientOptions = {
  endpoint?: IpcEndpoint;
  clientType: string;
  requestTimeoutMs?: number;
  reconnectDelayMs?: number;
  maxFrameBytes?: number;
};

type PendingRequest = {
  method: string;
  timer: NodeJS.Timeout;
  resolve: (frame: IpcFrame) => void;
  reject: (error: Error) => void;
};

const WINDOWS_CODEX_PIPE = "\\\\.\\pipe\\codex-ipc";

export class CodexIpcClient {
  private readonly endpoint: IpcEndpoint;
  private readonly clientType: string;
  private readonly requestTimeoutMs: number;
  private readonly reconnectDelayMs: number;
  private readonly decoder: FrameDecoder;
  private readonly events = new EventEmitter();
  private readonly pending = new Map<string, PendingRequest>();
  private socket: Socket | null = null;
  private connectPromise: Promise<void> | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private clientId: string | null = null;
  private disposed = false;
  private currentStatus: IpcConnectionStatus = "disconnected";

  constructor(options: CodexIpcClientOptions) {
    this.endpoint = options.endpoint ?? WINDOWS_CODEX_PIPE;
    this.clientType = options.clientType;
    this.requestTimeoutMs = options.requestTimeoutMs ?? 10_000;
    this.reconnectDelayMs = options.reconnectDelayMs ?? 1_000;
    this.decoder = new FrameDecoder(options.maxFrameBytes ?? DEFAULT_MAX_FRAME_BYTES);
  }

  get status(): IpcConnectionStatus {
    return this.currentStatus;
  }

  get id(): string | null {
    return this.clientId;
  }

  async connect(): Promise<void> {
    if (this.disposed) throw new Error("ipc-client-disposed");
    if (this.currentStatus === "connected") return;
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = this.openAndInitialize()
      .catch((error) => {
        this.emitError(error);
        this.scheduleReconnect();
        throw error;
      })
      .finally(() => {
        this.connectPromise = null;
      });
    return this.connectPromise;
  }

  async request(
    method: string,
    params: unknown,
    options: { version: number; targetClientId?: string },
  ): Promise<IpcFrame> {
    await this.connect();
    return this.sendRequest(method, params, options);
  }

  async broadcast(
    method: string,
    params: unknown,
    options: { version: number; targetClientIds?: string[] },
  ): Promise<void> {
    await this.connect();
    const socket = this.socket;
    if (!socket?.writable) throw new Error("ipc-not-connected");
    const frame: IpcFrame = {
      type: "broadcast",
      sourceClientId: this.clientId ?? "initializing-client",
      method,
      params,
      version: options.version,
      ...(options.targetClientIds
        ? { targetClientIds: options.targetClientIds }
        : {}),
    };
    await new Promise<void>((resolve, reject) => {
      socket.write(encodeFrame(frame), (error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  }

  onBroadcast(listener: IpcBroadcastListener): () => void {
    this.events.on("broadcast", listener);
    return () => this.events.off("broadcast", listener);
  }

  onStatus(listener: (status: IpcConnectionStatus) => void): () => void {
    this.events.on("status", listener);
    return () => this.events.off("status", listener);
  }

  onError(listener: (error: Error) => void): () => void {
    this.events.on("client-error", listener);
    return () => this.events.off("client-error", listener);
  }

  dispose(): void {
    this.disposed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.rejectPending(new Error("ipc-client-disposed"));
    this.socket?.destroy();
    this.socket = null;
    this.setStatus("disconnected");
    this.events.removeAllListeners();
  }

  private async openAndInitialize(): Promise<void> {
    this.setStatus("connecting");
    const socket = await this.openSocket();
    if (this.disposed) {
      socket.destroy();
      throw new Error("ipc-client-disposed");
    }
    this.socket = socket;
    this.decoder.reset();
    socket.on("data", (chunk) => this.handleData(chunk));
    socket.on("error", (error) => this.emitError(error));
    socket.on("close", () => this.handleClose(socket));

    try {
      const response = await this.sendRequest(
        "initialize",
        { clientType: this.clientType },
        { version: 0 },
      );
      const id = response.result?.clientId;
      if (response.resultType !== "success" || typeof id !== "string") {
        throw new Error(response.error ?? "ipc-initialize-failed");
      }
      this.clientId = id;
      this.setStatus("connected");
    } catch (error) {
      socket.destroy();
      throw error;
    }
  }

  private openSocket(): Promise<Socket> {
    return new Promise((resolve, reject) => {
      const socket =
        typeof this.endpoint === "string"
          ? createConnection(this.endpoint)
          : createConnection(this.endpoint.port, this.endpoint.host);
      const fail = (error: Error) => {
        clearTimeout(timer);
        socket.destroy();
        reject(error);
      };
      const timer = setTimeout(() => fail(new Error("ipc-connect-timeout")), this.requestTimeoutMs);
      socket.once("error", fail);
      socket.once("connect", () => {
        clearTimeout(timer);
        socket.off("error", fail);
        resolve(socket);
      });
    });
  }

  private sendRequest(
    method: string,
    params: unknown,
    options: { version: number; targetClientId?: string },
  ): Promise<IpcFrame> {
    const socket = this.socket;
    if (!socket?.writable) return Promise.reject(new Error("ipc-not-connected"));
    const requestId = randomUUID();
    const frame: IpcFrame = {
      type: "request",
      requestId,
      sourceClientId: this.clientId ?? "initializing-client",
      method,
      params,
      version: options.version,
      ...(options.targetClientId
        ? { targetClientId: options.targetClientId }
        : {}),
    };
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`ipc-request-timeout:${method}`));
        // A writable but stalled pipe needs a fresh handshake and subscriptions.
        if (this.socket === socket) socket.destroy();
      }, this.requestTimeoutMs);
      this.pending.set(requestId, { method, timer, resolve, reject });
      try {
        socket.write(encodeFrame(frame));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(requestId);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  private handleData(chunk: Buffer): void {
    let frames: IpcFrame[];
    try {
      frames = this.decoder.push(chunk);
    } catch (error) {
      this.emitError(error);
      this.socket?.destroy();
      return;
    }
    for (const frame of frames) this.handleFrame(frame);
  }

  private handleFrame(frame: IpcFrame): void {
    if (frame.type === "response" && frame.requestId) {
      const pending = this.pending.get(frame.requestId);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(frame.requestId);
      pending.resolve(frame);
      return;
    }
    if (frame.type === "client-discovery-request" && frame.requestId) {
      this.socket?.write(
        encodeFrame({
          type: "client-discovery-response",
          requestId: frame.requestId,
          response: { canHandle: false },
        }),
      );
      return;
    }
    if (frame.type === "broadcast") this.events.emit("broadcast", frame);
  }

  private handleClose(socket: Socket): void {
    if (this.socket !== socket) return;
    this.socket = null;
    this.clientId = null;
    this.decoder.reset();
    this.rejectPending(new Error("ipc-connection-closed"));
    this.setStatus("disconnected");
    this.scheduleReconnect();
  }

  private rejectPending(error: Error): void {
    for (const [requestId, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(requestId);
    }
  }

  private setStatus(status: IpcConnectionStatus): void {
    if (status === this.currentStatus) return;
    this.currentStatus = status;
    this.events.emit("status", status);
  }

  private emitError(error: unknown): void {
    this.events.emit(
      "client-error",
      error instanceof Error ? error : new Error(String(error)),
    );
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connect().catch(() => undefined);
    }, this.reconnectDelayMs);
  }
}
