import { describe, expect, it, vi } from "vitest";

import { normalizeVoiceWav } from "../src/asr/wav.js";
import { WhisperHttpClient } from "../src/asr/whisper.js";

describe("voice transcription", () => {
  it("converts One S 22050 Hz unsigned 8-bit WAV to 16000 Hz signed 16-bit", () => {
    const source = wav8(22_050, 2_205);
    const normalized = normalizeVoiceWav(source, 15);

    expect(normalized.subarray(0, 4).toString("ascii")).toBe("RIFF");
    expect(normalized.readUInt32LE(24)).toBe(16_000);
    expect(normalized.readUInt16LE(22)).toBe(1);
    expect(normalized.readUInt16LE(34)).toBe(16);
    expect(normalized.readUInt32LE(40)).toBe(3_200);
  });

  it("returns trimmed Chinese text from local whisper-server", async () => {
    const fetcher = vi.fn(async () =>
      new Response(JSON.stringify({ text: "  请继续运行测试  " }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const whisper = new WhisperHttpClient(
      "http://127.0.0.1:8178/inference",
      fetcher,
    );

    await expect(whisper.transcribe(wav8(22_050, 2_205))).resolves.toBe(
      "请继续运行测试",
    );
    expect(fetcher).toHaveBeenCalledOnce();
  });
});

function wav8(sampleRate: number, frameCount: number): Buffer {
  const data = Buffer.alloc(frameCount, 128);
  const output = Buffer.alloc(44 + data.length);
  output.write("RIFF", 0, "ascii");
  output.writeUInt32LE(output.length - 8, 4);
  output.write("WAVE", 8, "ascii");
  output.write("fmt ", 12, "ascii");
  output.writeUInt32LE(16, 16);
  output.writeUInt16LE(1, 20);
  output.writeUInt16LE(1, 22);
  output.writeUInt32LE(sampleRate, 24);
  output.writeUInt32LE(sampleRate, 28);
  output.writeUInt16LE(1, 32);
  output.writeUInt16LE(8, 34);
  output.write("data", 36, "ascii");
  output.writeUInt32LE(data.length, 40);
  data.copy(output, 44);
  return output;
}
