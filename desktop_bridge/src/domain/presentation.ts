import type { ThreadStream } from "./store.js";

export type TimelineItem = {
  id: string;
  turnId: string;
  kind: "user" | "assistant" | "command" | "file" | "plan" | "status";
  text: string;
  status?: string;
};

export type TaskDetail = {
  threadId: string;
  title: string;
  status: string;
  revision: number;
  items: TimelineItem[];
  cwd?: string;
  cwdGroupKey?: string;
  cwdGroupLabel?: string;
  gitInfo?: GitInfoSummary;
  settings?: ThreadSettingsSummary;
  activeTurnId?: string;
};

export type GitInfoSummary = {
  branch?: string;
  repositoryRoot?: string;
  sha?: string;
  isDirty?: boolean;
};

export type ThreadSettingsSummary = {
  model?: string;
  effort?: string;
  serviceTier?: string;
};

export type ThreadPresentationMetadata = {
  cwd?: string;
  cwdGroupKey?: string;
  cwdGroupLabel?: string;
  gitInfo?: GitInfoSummary;
  settings?: ThreadSettingsSummary;
  activeTurnId?: string;
};

export type TaskDiff = {
  threadId: string;
  revision: number;
  turns: Array<{
    turnId: string;
    status: string;
    unifiedDiff: string;
  }>;
  files: Array<{
    turnId: string;
    itemId: string;
    path: string;
    kind: string;
    unifiedDiff: string;
  }>;
};

const MAX_ITEM_TEXT = 4_000;
const MAX_ITEMS = 200;

export function presentThread(thread: ThreadStream): TaskDetail {
  const turns = orderedTurns(thread.state);
  const items: TimelineItem[] = [];
  for (let turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    const turn = asRecord(turns[turnIndex]);
    if (!turn) continue;
    const turnId = readString(turn.id) || readString(turn.turnId) || `turn-${turnIndex}`;
    const turnStatus = readStatus(turn.status);
    const rawItems = Array.isArray(turn.items) ? turn.items : [];
    for (let itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      const item = asRecord(rawItems[itemIndex]);
      if (!item) continue;
      const presented = presentItem(item, turnId, itemIndex, turnStatus);
      if (presented) items.push(presented);
    }
  }
  return {
    threadId: thread.threadId,
    title: readString(thread.state.title) || readString(thread.state.name) || "Untitled task",
    status: threadStatus(thread.state),
    revision: thread.revision,
    items: items.slice(-MAX_ITEMS),
    ...presentThreadMetadata(thread.state),
  };
}

export function presentThreadMetadata(
  state: Record<string, unknown>,
): ThreadPresentationMetadata {
  const cwd = extractCwd(state);
  const gitInfo = extractGitInfo(state);
  const settings = extractThreadSettings(state);
  const activeTurnId = extractActiveTurnId(state);
  return {
    ...(cwd
      ? {
          cwd,
          cwdGroupKey: normalizeCwdGroupKey(cwd),
          cwdGroupLabel: cwdLabel(cwd),
        }
      : {}),
    ...(gitInfo ? { gitInfo } : {}),
    ...(settings ? { settings } : {}),
    ...(activeTurnId ? { activeTurnId } : {}),
  };
}

export function presentThreadDiff(thread: ThreadStream): TaskDiff {
  const turns = orderedTurns(thread.state);
  const result: TaskDiff = {
    threadId: thread.threadId,
    revision: thread.revision,
    turns: [],
    files: [],
  };
  for (let turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    const turn = asRecord(turns[turnIndex]);
    if (!turn) continue;
    const turnId = readString(turn.id) || readString(turn.turnId) || `turn-${turnIndex}`;
    const status = readStatus(turn.status);
    const turnDiff = readUnifiedDiff(
      turn.unifiedDiff ?? turn.diff ?? asRecord(turn.output)?.unifiedDiff,
    );
    if (turnDiff) result.turns.push({ turnId, status, unifiedDiff: turnDiff });
    const items = Array.isArray(turn.items) ? turn.items : [];
    for (let itemIndex = 0; itemIndex < items.length; itemIndex += 1) {
      const item = asRecord(items[itemIndex]);
      if (!item || !isFileChangeType(readString(item.type))) continue;
      const itemId = readString(item.id) || `${turnId}-${itemIndex}`;
      const changes = Array.isArray(item.changes) ? item.changes : [item];
      for (const rawChange of changes) {
        const change = asRecord(rawChange);
        if (!change) continue;
        const unifiedDiff = readUnifiedDiff(
          change.unifiedDiff ?? change.diff ?? change.patch,
        );
        if (!unifiedDiff) continue;
        result.files.push({
          turnId,
          itemId,
          path: readString(change.path) || readString(change.filePath),
          kind: readString(change.kind) || readString(change.type) || "update",
          unifiedDiff,
        });
      }
    }
  }
  return result;
}

function orderedTurns(state: Record<string, unknown>): unknown[] {
  const direct = Array.isArray(state.turns) ? state.turns : [];
  if (direct.length > 0) return direct;
  const turnHistory = asRecord(state.turnHistory);
  const history = asRecord(turnHistory?.history);
  const entities = asRecord(history?.entitiesByKey);
  const islands = Array.isArray(history?.islands) ? history.islands : [];
  if (!entities || islands.length === 0) return [];
  const turns: unknown[] = [];
  const seen = new Set<string>();
  for (const islandValue of islands) {
    const island = asRecord(islandValue);
    const entries = Array.isArray(island?.entries) ? island.entries : [];
    for (const entryValue of entries) {
      const entry = asRecord(entryValue);
      const key = readString(entry?.value) || readString(entry?.key);
      if (!key || seen.has(key) || !asRecord(entities[key])) continue;
      seen.add(key);
      turns.push(entities[key]);
    }
  }
  return turns;
}

function presentItem(
  item: Record<string, unknown>,
  turnId: string,
  index: number,
  turnStatus: string,
): TimelineItem | null {
  const rawType = readString(item.type);
  const type = rawType.toLowerCase().replace(/[-_]/g, "");
  const id = readString(item.id) || `${turnId}-${index}`;
  const status = readStatus(item.status) || turnStatus;
  if (type === "usermessage" || type === "user" || type === "steeringusermessage") {
    return timeline(id, turnId, "user", readText(item.content ?? item.input ?? item.text), status);
  }
  if (type === "agentmessage" || type === "assistantmessage" || type === "assistant") {
    return timeline(id, turnId, "assistant", readText(item.text ?? item.content), status);
  }
  if (type.includes("plan")) {
    const text = readText(item.text ?? item.explanation ?? item.plan ?? item.steps);
    return timeline(id, turnId, "plan", text, status);
  }
  if (type === "commandexecution" || type === "command") {
    const command = sanitizeTerminalText(
      readString(item.command) || readString(item.cmd) || readString(item.commandLine),
    );
    const output = sanitizeTerminalText(readText(
      item.aggregatedOutput ?? item.aggregated_output ?? item.output ?? item.stderr ?? item.stdout,
    ));
    return timeline(
      id,
      turnId,
      "command",
      [command ? `$ ${command}` : "Command", output].filter(Boolean).join("\n"),
      status,
    );
  }
  if (isFileChangeType(rawType)) {
    const changes = Array.isArray(item.changes) ? item.changes : [item];
    const paths = changes
      .map((entry) => asRecord(entry))
      .map((entry) => readString(entry?.path) || readString(entry?.filePath))
      .filter(Boolean);
    return timeline(id, turnId, "file", paths.length ? paths.join("\n") : "Files changed", status);
  }
  if (type === "reasoning") {
    return timeline(id, turnId, "status", readText(item.summary ?? item.content ?? item.text), status);
  }
  return null;
}

export function sanitizeTerminalText(value: string): string {
  return stripTerminalControls(
    value.replace(/\r\n/g, "\n").replace(/\r/g, "\n"),
  ).trim();
}

function stripTerminalControls(value: string): string {
  let output = "";
  let index = 0;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    if (code === 0x1b) {
      const next = value.charCodeAt(index + 1);
      if (next === 0x5b) index = consumeCsi(value, index + 2);
      else if (next === 0x5d) index = consumeControlString(value, index + 2, true);
      else if (next === 0x50 || next === 0x58 || next === 0x5e || next === 0x5f) {
        index = consumeControlString(value, index + 2, false);
      } else {
        index = consumeEscapeSequence(value, index + 1);
      }
      continue;
    }
    if (code === 0x9b) {
      index = consumeCsi(value, index + 1);
      continue;
    }
    if (code === 0x9d) {
      index = consumeControlString(value, index + 1, true);
      continue;
    }
    if (code === 0x90 || code === 0x98 || code === 0x9e || code === 0x9f) {
      index = consumeControlString(value, index + 1, false);
      continue;
    }
    if (
      (code >= 0x00 && code <= 0x08) ||
      (code >= 0x0b && code <= 0x1f) ||
      (code >= 0x7f && code <= 0x9f)
    ) {
      index += 1;
      continue;
    }
    output += value[index];
    index += 1;
  }
  return output;
}

function consumeCsi(value: string, start: number): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    index += 1;
    if (code >= 0x40 && code <= 0x7e) return index;
  }
  return value.length;
}

function consumeControlString(
  value: string,
  start: number,
  allowBell: boolean,
): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    if (allowBell && code === 0x07) return index + 1;
    if (code === 0x9c) return index + 1;
    if (code === 0x1b && value.charCodeAt(index + 1) === 0x5c) {
      return index + 2;
    }
    index += 1;
  }
  return value.length;
}

function consumeEscapeSequence(value: string, start: number): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    index += 1;
    if (code >= 0x30 && code <= 0x7e) return index;
    if (code < 0x20 || code > 0x2f) return index;
  }
  return value.length;
}

function timeline(
  id: string,
  turnId: string,
  kind: TimelineItem["kind"],
  text: string,
  status: string,
): TimelineItem | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  return {
    id,
    turnId,
    kind,
    text: trimmed.slice(-MAX_ITEM_TEXT),
    ...(status ? { status } : {}),
  };
}

function readText(value: unknown): string {
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return value.map(readText).filter(Boolean).join("\n");
  const record = asRecord(value);
  if (!record) return "";
  return (
    readString(record.text) ||
    readText(record.content) ||
    readString(record.message) ||
    readString(record.value) ||
    readString(record.step) ||
    readString(record.title)
  );
}

function threadStatus(state: Record<string, unknown>): string {
  const runtime = asRecord(state.threadRuntimeStatus);
  const runtimeType = readStatus(runtime?.type ?? state.status);
  if (runtimeType) return runtimeType;
  const turns = orderedTurns(state);
  return readStatus(asRecord(turns.at(-1))?.status) || "idle";
}

function extractCwd(state: Record<string, unknown>): string {
  const thread = asRecord(state.thread);
  const metadata = asRecord(state.metadata);
  const settings = asRecord(state.latestThreadSettings) ?? asRecord(state.threadSettings);
  return (
    readString(state.cwd) ||
    readString(state.workingDirectory) ||
    readString(thread?.cwd) ||
    readString(metadata?.cwd) ||
    readString(settings?.cwd)
  );
}

function extractGitInfo(state: Record<string, unknown>): GitInfoSummary | null {
  const source = asRecord(state.gitInfo) ?? asRecord(asRecord(state.metadata)?.gitInfo);
  if (!source) return null;
  const branch =
    readString(source.branch) ||
    readString(source.currentBranch) ||
    readString(asRecord(source.branch)?.name);
  const repositoryRoot =
    readString(source.repositoryRoot) ||
    readString(source.root) ||
    readString(source.worktreeRoot);
  const sha = readString(source.sha) || readString(source.head) || readString(source.commit);
  const isDirty = typeof source.isDirty === "boolean" ? source.isDirty : undefined;
  const result: GitInfoSummary = {
    ...(branch ? { branch } : {}),
    ...(repositoryRoot ? { repositoryRoot } : {}),
    ...(sha ? { sha } : {}),
    ...(isDirty !== undefined ? { isDirty } : {}),
  };
  return Object.keys(result).length > 0 ? result : null;
}

function extractThreadSettings(
  state: Record<string, unknown>,
): ThreadSettingsSummary | null {
  const source = asRecord(state.latestThreadSettings) ?? asRecord(state.threadSettings);
  if (!source) return null;
  const model = readString(source.model);
  const effort = readString(source.effort) || readString(source.reasoningEffort);
  const serviceTier = readString(source.serviceTier);
  const result: ThreadSettingsSummary = {
    ...(model ? { model } : {}),
    ...(effort ? { effort } : {}),
    ...(serviceTier ? { serviceTier } : {}),
  };
  return Object.keys(result).length > 0 ? result : null;
}

function extractActiveTurnId(state: Record<string, unknown>): string {
  const runtime = asRecord(state.threadRuntimeStatus);
  const direct =
    readString(runtime?.turnId) ||
    readString(runtime?.activeTurnId) ||
    readString(state.activeTurnId);
  if (direct) return direct;
  const turns = orderedTurns(state);
  for (let index = turns.length - 1; index >= 0; index -= 1) {
    const turn = asRecord(turns[index]);
    if (!turn) continue;
    const status = readStatus(turn.status).toLowerCase();
    if (status === "inprogress" || status === "active" || status === "running") {
      return readString(turn.id) || readString(turn.turnId);
    }
  }
  return "";
}

function normalizeCwdGroupKey(cwd: string): string {
  const normalized = cwd.replace(/\\/g, "/").replace(/\/+$/, "");
  return /^[A-Za-z]:\//.test(normalized) ? normalized.toLowerCase() : normalized;
}

function cwdLabel(cwd: string): string {
  const parts = cwd.replace(/\\/g, "/").replace(/\/+$/, "").split("/");
  return parts.at(-1) || cwd;
}

function isFileChangeType(value: string): boolean {
  const type = value.toLowerCase().replace(/[-_]/g, "");
  return type === "filechange" || type.includes("patch");
}

function readUnifiedDiff(value: unknown): string {
  if (typeof value === "string") return value;
  const record = asRecord(value);
  if (!record) return "";
  const nested = record.unifiedDiff ?? record.diff ?? record.patch ?? record.text;
  return typeof nested === "string" ? nested : "";
}

function readStatus(value: unknown): string {
  if (typeof value === "string") return value;
  const record = asRecord(value);
  return record ? readStatus(record.type ?? record.status ?? record.state) : "";
}

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}
