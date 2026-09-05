import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export type SignedRequest = {
  token: string;
  method: string;
  path: string;
  timestamp: string;
  requestId: string;
  signature: string;
  body: Buffer;
};

export class RequestAuthenticator {
  private readonly seen = new Map<string, number>();

  constructor(
    private readonly now: () => number = Date.now,
    private readonly allowedSkewMs = 60_000,
  ) {}

  verify(request: SignedRequest): true {
    const timestampSeconds = Number(request.timestamp);
    if (!Number.isFinite(timestampSeconds)) {
      throw new Error("invalid-request-timestamp");
    }
    const timestampMs = timestampSeconds * 1_000;
    if (Math.abs(this.now() - timestampMs) > this.allowedSkewMs) {
      throw new Error("request-timestamp-out-of-range");
    }
    this.prune();
    if (this.seen.has(request.requestId)) throw new Error("request-replayed");

    const bodyHash = createHash("sha256").update(request.body).digest("hex");
    const expected = createHmac("sha256", request.token)
      .update(
        [
          request.method.toUpperCase(),
          request.path,
          request.timestamp,
          request.requestId,
          bodyHash,
        ].join("\n"),
      )
      .digest();
    const actual = Buffer.from(request.signature, "hex");
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
      throw new Error("invalid-request-signature");
    }
    this.seen.set(request.requestId, this.now() + this.allowedSkewMs);
    return true;
  }

  private prune(): void {
    const now = this.now();
    for (const [requestId, expiresAt] of this.seen) {
      if (expiresAt <= now) this.seen.delete(requestId);
    }
  }
}
