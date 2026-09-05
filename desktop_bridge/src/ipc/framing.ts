import type { IpcFrame } from "./types.js";

export const DEFAULT_MAX_FRAME_BYTES = 64 * 1024 * 1024;

export function encodeFrame(frame: IpcFrame | Record<string, unknown>): Buffer {
  const payload = Buffer.from(JSON.stringify(frame), "utf8");
  if (payload.length === 0 || payload.length > 0xffff_ffff) {
    throw new Error("invalid-frame-length");
  }
  const header = Buffer.allocUnsafe(4);
  header.writeUInt32LE(payload.length, 0);
  return Buffer.concat([header, payload]);
}

export class FrameDecoder {
  private incoming = Buffer.alloc(0);

  constructor(private readonly maxFrameBytes = DEFAULT_MAX_FRAME_BYTES) {}

  push(chunk: Buffer): IpcFrame[] {
    this.incoming = Buffer.concat([this.incoming, chunk]);
    const frames: IpcFrame[] = [];
    while (this.incoming.length >= 4) {
      const length = this.incoming.readUInt32LE(0);
      if (length === 0 || length > this.maxFrameBytes) {
        this.incoming = Buffer.alloc(0);
        throw new Error(`invalid-frame-length:${length}`);
      }
      if (this.incoming.length < length + 4) break;
      const payload = this.incoming.subarray(4, length + 4).toString("utf8");
      this.incoming = this.incoming.subarray(length + 4);
      const value: unknown = JSON.parse(payload);
      if (value === null || typeof value !== "object") {
        throw new Error("invalid-ipc-frame");
      }
      frames.push(value as IpcFrame);
    }
    return frames;
  }

  reset(): void {
    this.incoming = Buffer.alloc(0);
  }
}
