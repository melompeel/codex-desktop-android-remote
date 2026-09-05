import { createHash, randomBytes, randomInt, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export type DeviceKind = "android" | "ones";

export type DeviceRecord = {
  deviceId: string;
  name: string;
  kind: DeviceKind;
  tokenHash: string;
  createdAt: number;
};

export type IssuedCredential = {
  deviceId: string;
  token: string;
};

export interface DeviceRegistry {
  issue(deviceId: string, name: string, kind: DeviceKind): Promise<IssuedCredential>;
  revoke(deviceId: string): Promise<boolean>;
  findByToken(token: string): DeviceRecord | null;
  list(): DeviceRecord[];
}

export class MemoryDeviceRegistry implements DeviceRegistry {
  protected readonly records = new Map<string, DeviceRecord>();

  async issue(
    deviceId: string,
    name: string,
    kind: DeviceKind,
  ): Promise<IssuedCredential> {
    const token = randomBytes(32).toString("hex");
    this.records.set(deviceId, {
      deviceId,
      name: name.trim().slice(0, 80) || kind,
      kind,
      tokenHash: hashToken(token),
      createdAt: Date.now(),
    });
    return { deviceId, token };
  }

  async revoke(deviceId: string): Promise<boolean> {
    return this.records.delete(deviceId);
  }

  findByToken(token: string): DeviceRecord | null {
    const hash = hashToken(token);
    return (
      [...this.records.values()].find((record) => record.tokenHash === hash) ??
      null
    );
  }

  list(): DeviceRecord[] {
    return [...this.records.values()].map((record) => ({ ...record }));
  }
}

export class FileDeviceRegistry extends MemoryDeviceRegistry {
  private persistTail: Promise<void> = Promise.resolve();

  private constructor(private readonly filePath: string) {
    super();
  }

  static async open(filePath: string): Promise<FileDeviceRegistry> {
    const registry = new FileDeviceRegistry(filePath);
    try {
      const raw = JSON.parse(await readFile(filePath, "utf8")) as unknown;
      if (Array.isArray(raw)) {
        for (const entry of raw) {
          if (isDeviceRecord(entry)) registry.records.set(entry.deviceId, entry);
        }
      }
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code !== "ENOENT") throw error;
    }
    return registry;
  }

  override async issue(
    deviceId: string,
    name: string,
    kind: DeviceKind,
  ): Promise<IssuedCredential> {
    const credential = await super.issue(deviceId, name, kind);
    const tokenHash = hashToken(credential.token);
    try {
      await this.queuePersist();
      return credential;
    } catch (error) {
      if (this.records.get(deviceId)?.tokenHash === tokenHash) {
        this.records.delete(deviceId);
      }
      throw error;
    }
  }

  override async revoke(deviceId: string): Promise<boolean> {
    const existing = this.records.get(deviceId);
    if (!existing) return false;
    this.records.delete(deviceId);
    try {
      await this.queuePersist();
      return true;
    } catch (error) {
      this.records.set(deviceId, existing);
      throw error;
    }
  }

  async persist(): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true });
    const temporary = `${this.filePath}.${process.pid}.tmp`;
    await writeFile(
      temporary,
      `${JSON.stringify(this.list(), null, 2)}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    await rename(temporary, this.filePath);
  }

  private queuePersist(): Promise<void> {
    const operation = this.persistTail.catch(() => undefined).then(() => this.persist());
    this.persistTail = operation;
    return operation;
  }
}

export class PairingService {
  private code: string;
  private expiresAt: number;
  private pairingInProgress = false;

  constructor(
    private readonly registry: DeviceRegistry,
    initialCode = generatePairingCode(),
    private readonly now: () => number = Date.now,
  ) {
    this.code = initialCode;
    this.expiresAt = this.now() + 10 * 60_000;
  }

  get currentCode(): string {
    return this.code;
  }

  get codeExpiresAt(): number {
    return this.expiresAt;
  }

  async pair(input: {
    code: string;
    name: string;
    kind: DeviceKind;
  }): Promise<IssuedCredential> {
    if (this.pairingInProgress) throw new Error("pairing-in-progress");
    this.pairingInProgress = true;
    try {
      if (this.now() >= this.expiresAt) throw new Error("pairing-code-expired");
      if (input.code !== this.code) throw new Error("pairing-code-invalid");
      const credential = await this.registry.issue(
        randomUUID(),
        input.name,
        input.kind,
      );
      this.code = generatePairingCode();
      this.expiresAt = this.now() + 10 * 60_000;
      return credential;
    } finally {
      this.pairingInProgress = false;
    }
  }
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

function generatePairingCode(): string {
  return String(randomInt(0, 1_000_000)).padStart(6, "0");
}

function isDeviceRecord(value: unknown): value is DeviceRecord {
  if (value === null || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.deviceId === "string" &&
    typeof record.name === "string" &&
    (record.kind === "android" || record.kind === "ones") &&
    typeof record.tokenHash === "string" &&
    typeof record.createdAt === "number"
  );
}
