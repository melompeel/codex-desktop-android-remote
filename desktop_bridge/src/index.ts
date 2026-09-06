import { homedir, networkInterfaces } from "node:os";
import { join } from "node:path";

import { AttachmentStore } from "./attachments/store.js";
import { BridgeController } from "./bridge/controller.js";
import {
  UnavailableTranscriber,
  WhisperHttpClient,
} from "./asr/whisper.js";
import { AppServerCatalog } from "./catalog/app-server.js";
import {
  AppServerTaskCreator,
  openStdioAppServer,
} from "./catalog/task-creator.js";
import { BridgeStore } from "./domain/store.js";
import { createBridgeApp } from "./http/app.js";
import {
  CodexIpcAdapter,
  CURRENT_DESKTOP_ADAPTER,
} from "./ipc/adapter.js";
import { CodexIpcClient } from "./ipc/client.js";
import {
  FileDeviceRegistry,
  PairingService,
} from "./security/device-registry.js";
import {
  detectDesktopPackageVersion,
  findCodexRuntime,
} from "./runtime/desktop.js";

const host = process.env.BRIDGE_HOST ?? "0.0.0.0";
const port = Number(process.env.BRIDGE_PORT ?? 8765);
const dataRoot =
  process.env.BRIDGE_DATA_DIR ??
  join(process.env.APPDATA ?? homedir(), "OneSCodexRemote");
const codexHome = process.env.CODEX_HOME ?? join(homedir(), ".codex");

const desktopVersion = await detectDesktopPackageVersion();
const runtime = await findCodexRuntime();
const store = new BridgeStore();
const ipc = new CodexIpcClient({ clientType: "codex-desktop-android-remote" });
const adapter = new CodexIpcAdapter(
  ipc,
  CURRENT_DESKTOP_ADAPTER,
  desktopVersion,
  runtime.version,
);
const catalog = new AppServerCatalog(async () => (await findCodexRuntime()).executable);
const taskCreator = new AppServerTaskCreator(
  () => openStdioAppServer(runtime.executable),
);
const controller = new BridgeController(
  adapter,
  store,
  catalog,
  taskCreator,
);
const registry = await FileDeviceRegistry.open(join(dataRoot, "devices.json"));
const pairing = new PairingService(registry, process.env.BRIDGE_PAIRING_CODE);
const attachments = await AttachmentStore.open(
  join(codexHome, "attachments", "codex-remote"),
);
const transcriber = process.env.WHISPER_SERVER_URL
  ? new WhisperHttpClient(process.env.WHISPER_SERVER_URL)
  : new UnavailableTranscriber();

ipc.onBroadcast((frame) => {
  try {
    controller.ingestIpcFrame(frame);
  } catch (error) {
    store.appendEvent("protocol.error", {
      error: error instanceof Error ? error.message : String(error),
    });
  }
});
ipc.onStatus((status) => {
  store.appendEvent("ipc.status", { status });
  if (status === "connected") {
    void controller.restoreFollowing();
    void adapter.requestFollowingStatus().catch((error) => {
      store.appendEvent("ipc.following_status_error", {
        error: error instanceof Error ? error.message : String(error),
      });
    });
  }
});
ipc.onError((error) => {
  store.appendEvent("ipc.error", { error: error.message });
  console.error(`Codex IPC: ${error.message}`);
});

await Promise.allSettled([ipc.connect(), catalog.start()]);

const app = createBridgeApp({
  controller,
  store,
  registry,
  pairing,
  ipcStatus: () => ipc.status,
  asrStatus: () => transcriber.status(),
  transcriber,
  attachments,
});

await app.listen({ host, port });
console.log(`Codex Desktop Android Remote Bridge listening on ${host}:${port}`);
console.log(`Codex Desktop ${desktopVersion ?? "connected"}`);
console.log(`Pairing code (10 minutes): ${pairing.currentCode}`);
for (const address of lanAddresses()) console.log(`LAN URL: http://${address}:${port}`);

const shutdown = async () => {
  ipc.dispose();
  catalog.dispose();
  await app.close();
  process.exit(0);
};
process.on("SIGINT", () => void shutdown());
process.on("SIGTERM", () => void shutdown());

function lanAddresses(): string[] {
  return Object.values(networkInterfaces())
    .flat()
    .filter(
      (entry): entry is NonNullable<typeof entry> =>
        entry != null && entry.family === "IPv4" && !entry.internal,
    )
    .map((entry) => entry.address);
}
