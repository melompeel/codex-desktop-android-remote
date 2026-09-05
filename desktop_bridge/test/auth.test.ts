import { createHash, createHmac } from "node:crypto";

import { describe, expect, it } from "vitest";

import { RequestAuthenticator } from "../src/security/request-auth.js";

function sign(
  token: string,
  method: string,
  path: string,
  timestamp: string,
  requestId: string,
  body: Buffer,
): string {
  const bodyHash = createHash("sha256").update(body).digest("hex");
  return createHmac("sha256", token)
    .update([method, path, timestamp, requestId, bodyHash].join("\n"))
    .digest("hex");
}

describe("request authentication", () => {
  it("accepts a fresh signed request once and rejects replay", () => {
    const auth = new RequestAuthenticator(() => 1_700_000_000_000);
    const token = "0123456789abcdef0123456789abcdef";
    const body = Buffer.from('{"decision":"accept"}');
    const timestamp = "1700000000";
    const signature = sign(
      token,
      "POST",
      "/v1/approvals/a",
      timestamp,
      "request-1",
      body,
    );

    expect(
      auth.verify({
        token,
        method: "POST",
        path: "/v1/approvals/a",
        timestamp,
        requestId: "request-1",
        signature,
        body,
      }),
    ).toBe(true);
    expect(() =>
      auth.verify({
        token,
        method: "POST",
        path: "/v1/approvals/a",
        timestamp,
        requestId: "request-1",
        signature,
        body,
      }),
    ).toThrow("request-replayed");
  });

  it("rejects stale timestamps and invalid signatures", () => {
    const auth = new RequestAuthenticator(() => 1_700_000_000_000);
    const base = {
      token: "0123456789abcdef0123456789abcdef",
      method: "GET",
      path: "/v1/tasks",
      requestId: "request-2",
      body: Buffer.alloc(0),
    };

    expect(() =>
      auth.verify({ ...base, timestamp: "1699999900", signature: "00" }),
    ).toThrow("request-timestamp-out-of-range");
    expect(() =>
      auth.verify({ ...base, timestamp: "1700000000", signature: "00" }),
    ).toThrow("invalid-request-signature");
  });

  it("allows ordinary mobile clock drift but keeps a short expiry window", () => {
    const auth = new RequestAuthenticator(() => 1_700_000_000_000);
    const request = {
      token: "0123456789abcdef0123456789abcdef",
      method: "GET",
      path: "/v1/tasks",
      timestamp: "1699999941",
      requestId: "request-drift",
      body: Buffer.alloc(0),
    };

    expect(auth.verify({
      ...request,
      signature: sign(
        request.token,
        request.method,
        request.path,
        request.timestamp,
        request.requestId,
        request.body,
      ),
    })).toBe(true);
  });
});
