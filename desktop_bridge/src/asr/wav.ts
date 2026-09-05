type WavInfo = {
  channels: number;
  sampleRate: number;
  bitsPerSample: number;
  blockAlign: number;
  data: Buffer;
};

export function normalizeVoiceWav(source: Buffer, maxSeconds = 15): Buffer {
  const input = parsePcmWav(source);
  const frameCount = Math.floor(input.data.length / input.blockAlign);
  const duration = frameCount / input.sampleRate;
  if (duration <= 0) throw new Error("voice-audio-empty");
  if (duration > maxSeconds) throw new Error("voice-audio-too-long");

  const samples = new Float64Array(frameCount);
  for (let frame = 0; frame < frameCount; frame += 1) {
    let total = 0;
    for (let channel = 0; channel < input.channels; channel += 1) {
      const offset = frame * input.blockAlign + channel * (input.bitsPerSample / 8);
      total +=
        input.bitsPerSample === 8
          ? (input.data[offset]! - 128) / 128
          : input.data.readInt16LE(offset) / 32_768;
    }
    samples[frame] = total / input.channels;
  }

  const outputRate = 16_000;
  const outputFrames = Math.max(1, Math.round(frameCount * outputRate / input.sampleRate));
  const pcm = Buffer.alloc(outputFrames * 2);
  for (let index = 0; index < outputFrames; index += 1) {
    const position = index * input.sampleRate / outputRate;
    const left = Math.min(Math.floor(position), samples.length - 1);
    const right = Math.min(left + 1, samples.length - 1);
    const fraction = position - left;
    const sample = samples[left]! * (1 - fraction) + samples[right]! * fraction;
    pcm.writeInt16LE(Math.max(-32_768, Math.min(32_767, Math.round(sample * 32_767))), index * 2);
  }
  return createPcm16Wav(pcm, outputRate);
}

function parsePcmWav(source: Buffer): WavInfo {
  if (
    source.length < 44 ||
    source.subarray(0, 4).toString("ascii") !== "RIFF" ||
    source.subarray(8, 12).toString("ascii") !== "WAVE"
  ) {
    throw new Error("voice-audio-invalid-wav");
  }
  let format: Omit<WavInfo, "data"> | null = null;
  let data: Buffer | null = null;
  for (let offset = 12; offset + 8 <= source.length;) {
    const id = source.subarray(offset, offset + 4).toString("ascii");
    const size = source.readUInt32LE(offset + 4);
    const start = offset + 8;
    const end = start + size;
    if (end > source.length) throw new Error("voice-audio-truncated");
    if (id === "fmt ") {
      if (size < 16 || source.readUInt16LE(start) !== 1) {
        throw new Error("voice-audio-unsupported-format");
      }
      const channels = source.readUInt16LE(start + 2);
      const sampleRate = source.readUInt32LE(start + 4);
      const blockAlign = source.readUInt16LE(start + 12);
      const bitsPerSample = source.readUInt16LE(start + 14);
      if (
        channels < 1 ||
        channels > 2 ||
        sampleRate < 8_000 ||
        sampleRate > 48_000 ||
        (bitsPerSample !== 8 && bitsPerSample !== 16) ||
        blockAlign !== channels * (bitsPerSample / 8)
      ) {
        throw new Error("voice-audio-unsupported-format");
      }
      format = { channels, sampleRate, bitsPerSample, blockAlign };
    } else if (id === "data") {
      data = source.subarray(start, end);
    }
    offset = end + (size % 2);
  }
  if (!format || !data) throw new Error("voice-audio-missing-chunk");
  return { ...format, data };
}

function createPcm16Wav(pcm: Buffer, sampleRate: number): Buffer {
  const output = Buffer.alloc(44 + pcm.length);
  output.write("RIFF", 0, "ascii");
  output.writeUInt32LE(output.length - 8, 4);
  output.write("WAVE", 8, "ascii");
  output.write("fmt ", 12, "ascii");
  output.writeUInt32LE(16, 16);
  output.writeUInt16LE(1, 20);
  output.writeUInt16LE(1, 22);
  output.writeUInt32LE(sampleRate, 24);
  output.writeUInt32LE(sampleRate * 2, 28);
  output.writeUInt16LE(2, 32);
  output.writeUInt16LE(16, 34);
  output.write("data", 36, "ascii");
  output.writeUInt32LE(pcm.length, 40);
  pcm.copy(output, 44);
  return output;
}
