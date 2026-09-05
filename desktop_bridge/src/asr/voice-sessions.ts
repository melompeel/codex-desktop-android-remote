import { randomUUID } from "node:crypto";

export type VoiceSession = {
  voiceId: string;
  sourceDeviceId: string;
  transcript: string;
  createdAt: number;
  expiresAt: number;
  claimedBy?: string;
};

export class VoiceSessionStore {
  private readonly sessions = new Map<string, VoiceSession>();

  constructor(private readonly now: () => number = Date.now) {}

  create(sourceDeviceId: string, transcript: string): VoiceSession {
    this.prune();
    const session: VoiceSession = {
      voiceId: randomUUID(),
      sourceDeviceId,
      transcript,
      createdAt: this.now(),
      expiresAt: this.now() + 5 * 60_000,
    };
    this.sessions.set(session.voiceId, session);
    return { ...session };
  }

  get(voiceId: string): VoiceSession {
    this.prune();
    const session = this.sessions.get(voiceId);
    if (!session) throw new Error("voice-session-not-found");
    return { ...session };
  }

  claim(voiceId: string, claimant: string): VoiceSession {
    this.prune();
    const session = this.sessions.get(voiceId);
    if (!session) throw new Error("voice-session-not-found");
    if (session.claimedBy) throw new Error("voice-session-already-resolved");
    session.claimedBy = claimant;
    return { ...session };
  }

  release(voiceId: string, claimant: string): void {
    const session = this.sessions.get(voiceId);
    if (session?.claimedBy === claimant) delete session.claimedBy;
  }

  cancel(voiceId: string): boolean {
    return this.sessions.delete(voiceId);
  }

  private prune(): void {
    const now = this.now();
    for (const [voiceId, session] of this.sessions) {
      if (session.expiresAt <= now) this.sessions.delete(voiceId);
    }
  }
}
