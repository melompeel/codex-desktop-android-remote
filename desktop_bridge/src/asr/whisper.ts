import { normalizeVoiceWav } from "./wav.js";

export interface VoiceTranscriber {
  status(): { available: boolean; reason?: string };
  transcribe(source: Buffer): Promise<string>;
}

export class WhisperHttpClient implements VoiceTranscriber {
  constructor(
    private readonly inferenceUrl: string,
    private readonly fetcher: typeof fetch = fetch,
    private readonly timeoutMs = 60_000,
  ) {}

  status(): { available: boolean } {
    return { available: true };
  }

  async transcribe(source: Buffer): Promise<string> {
    const wav = normalizeVoiceWav(source, 15);
    const form = new FormData();
    form.set("file", new Blob([new Uint8Array(wav)], { type: "audio/wav" }), "voice.wav");
    form.set("language", "zh");
    form.set("response_format", "json");
    const response = await this.fetcher(this.inferenceUrl, {
      method: "POST",
      body: form,
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) throw new Error(`whisper-http-${response.status}`);
    const payload = (await response.json()) as unknown;
    const rawText =
      payload !== null && typeof payload === "object"
        ? (payload as Record<string, unknown>).text
        : null;
    const text = typeof rawText === "string" ? rawText.trim() : "";
    if (!text) throw new Error("whisper-empty-transcript");
    return text;
  }
}

export class UnavailableTranscriber implements VoiceTranscriber {
  constructor(private readonly reason = "whisper-not-configured") {}

  status(): { available: false; reason: string } {
    return { available: false, reason: this.reason };
  }

  async transcribe(): Promise<string> {
    throw new Error(this.reason);
  }
}
