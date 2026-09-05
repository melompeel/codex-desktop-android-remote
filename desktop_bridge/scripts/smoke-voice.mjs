import { createHash, createHmac, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";

const bridgeUrl = (process.env.BRIDGE_URL ?? "http://127.0.0.1:8766").replace(/\/$/, "");
const pairingCode = requireEnvironment("BRIDGE_PAIRING_CODE");
const wavPath = requireEnvironment("VOICE_WAV_PATH");

const health = await readJson(await fetch(`${bridgeUrl}/v1/health`));
if (health.asr?.available !== true) throw new Error("bridge-whisper-unavailable");

const pairing = await readJson(
  await fetch(`${bridgeUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      code: pairingCode,
      name: "Voice smoke test",
      kind: "android",
    }),
  }),
);
if (typeof pairing.token !== "string") throw new Error("pairing-token-missing");

const wav = await readFile(wavPath);
const uploadPath = "/v1/voice";
const upload = await readJson(
  await fetch(`${bridgeUrl}${uploadPath}`, {
    method: "POST",
    headers: {
      ...signedHeaders(pairing.token, "POST", uploadPath, wav),
      "content-type": "audio/wav",
    },
    body: wav,
    signal: AbortSignal.timeout(90_000),
  }),
);
if (typeof upload.voiceId !== "string") throw new Error("voice-id-missing");

const cancelPath = `/v1/voice/${upload.voiceId}`;
await readJson(
  await fetch(`${bridgeUrl}${cancelPath}`, {
    method: "DELETE",
    headers: signedHeaders(pairing.token, "DELETE", cancelPath, Buffer.alloc(0)),
  }),
);

console.log(
  JSON.stringify({
    upload: "ok",
    transcript: upload.transcript,
    cancelled: true,
    ipc: health.ipc,
    desktopCompatible: health.compatibility?.supported === true,
    whisperAvailable: true,
  }),
);

function signedHeaders(token, method, path, body) {
  const timestamp = String(Math.floor(Date.now() / 1_000));
  const requestId = randomUUID();
  const bodyHash = createHash("sha256").update(body).digest("hex");
  const signature = createHmac("sha256", token)
    .update([method, path, timestamp, requestId, bodyHash].join("\n"))
    .digest("hex");
  return {
    authorization: `Bearer ${token}`,
    "x-request-id": requestId,
    "x-timestamp": timestamp,
    "x-signature": signature,
  };
}

async function readJson(response) {
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(payload.error ?? `http-${response.status}`);
  }
  return payload;
}

function requireEnvironment(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name}-required`);
  return value;
}
