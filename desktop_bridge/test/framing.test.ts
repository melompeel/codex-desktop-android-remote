import { describe, expect, it } from "vitest";

import { FrameDecoder, encodeFrame } from "../src/ipc/framing.js";

describe("Codex IPC framing", () => {
  it("decodes split and coalesced little-endian JSON frames", () => {
    const decoder = new FrameDecoder(1024);
    const first = encodeFrame({ type: "broadcast", method: "one" });
    const second = encodeFrame({ type: "response", requestId: "two" });

    expect(decoder.push(first.subarray(0, 3))).toEqual([]);
    expect(
      decoder.push(Buffer.concat([first.subarray(3), second])),
    ).toEqual([
      { type: "broadcast", method: "one" },
      { type: "response", requestId: "two" },
    ]);
  });

  it("rejects zero-length and oversized frames", () => {
    const decoder = new FrameDecoder(16);
    expect(() => decoder.push(Buffer.alloc(4))).toThrow("invalid-frame-length");

    const oversized = Buffer.alloc(4);
    oversized.writeUInt32LE(17);
    expect(() => new FrameDecoder(16).push(oversized)).toThrow(
      "invalid-frame-length",
    );
  });
});
