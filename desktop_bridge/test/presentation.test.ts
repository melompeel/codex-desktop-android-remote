import { describe, expect, it } from "vitest";

import {
  presentThread,
  presentThreadDiff,
  sanitizeTerminalText,
} from "../src/domain/presentation.js";

describe("task presentation", () => {
  it("summarizes messages and file changes without sending full diffs", () => {
    const detail = presentThread({
      threadId: "thread-1",
      revision: 8,
      state: {
        title: "Demo",
        threadRuntimeStatus: { type: "idle" },
        turns: [{
          id: "turn-1",
          status: "completed",
          items: [
            { id: "a", type: "agentMessage", text: "完成" },
            {
              id: "f",
              type: "fileChange",
              changes: [{ path: "src/app.ts", diff: "SECRET-DIFF" }],
            },
          ],
        }],
      },
    });

    expect(detail).toMatchObject({
      threadId: "thread-1",
      revision: 8,
      title: "Demo",
      items: [
        { kind: "assistant", text: "完成" },
        { kind: "file", text: "src/app.ts" },
      ],
    });
    expect(JSON.stringify(detail)).not.toContain("SECRET-DIFF");
  });

  it("reads v11 canonical turnHistory entities in island order", () => {
    const detail = presentThread({
      threadId: "thread-v11",
      revision: 2,
      state: {
        title: "Canonical",
        turns: [],
        turnHistory: {
          kind: "canonical",
          history: {
            entitiesByKey: {
              "turn:a": {
                turnId: "a",
                status: "completed",
                items: [{ id: "m1", type: "agentMessage", text: "first" }],
              },
              "turn:b": {
                turnId: "b",
                status: "inProgress",
                items: [{ id: "m2", type: "agentMessage", text: "second" }],
              },
            },
            islands: [{ entries: [{ key: "one", value: "turn:a" }, { key: "two", value: "turn:b" }] }],
          },
        },
      },
    });

    expect(detail.items.map((item) => item.text)).toEqual(["first", "second"]);
  });

  it("removes terminal escape sequences while preserving readable command output", () => {
    const detail = presentThread({
      threadId: "thread-terminal",
      revision: 3,
      state: {
        title: "Terminal output",
        turns: [{
          id: "turn-command",
          status: "completed",
          items: [{
            id: "command-1",
            type: "commandExecution",
            command: "npm test",
            aggregatedOutput: "\u001b[?25l\u001b[2J31 tests passed\u0000\r\n\u001b[0mDone",
          }],
        }],
      },
    });

    expect(detail.items).toMatchObject([{
      kind: "command",
      text: "$ npm test\n31 tests passed\nDone",
    }]);
    expect(detail.items[0]?.text).not.toContain("\u001b");
  });

  it("strips terminal control families without altering tabs or backslashes", () => {
    const controlled = [
      "before\rmiddle\r\nafter\tC:\\repo\\file.ts",
      "\u001b[?25lCSI\u001b[0m",
      "\u001b]0;window title\u0007OSC",
      "\u001bP1;2|device payload\u001b\\DCS",
      "\u001bXprivate\u001b\\SOS",
      "\u001b^private\u001b\\PM",
      "\u001b_private\u001b\\APC",
      "\u001b(Bcharset",
      "\u009b31mC1-CSI\u009b0m",
      "nul\u0000del\u007f",
    ].join("\n");

    expect(sanitizeTerminalText(controlled)).toBe([
      "before\nmiddle\nafter\tC:\\repo\\file.ts",
      "CSI",
      "OSC",
      "DCS",
      "SOS",
      "PM",
      "APC",
      "charset",
      "C1-CSI",
      "nuldel",
    ].join("\n"));
  });

  it("exposes workspace grouping, git metadata, settings, and the active turn", () => {
    const detail = presentThread({
      threadId: "thread-meta",
      revision: 4,
      state: {
        cwd: "C:\\Users\\melon\\repo\\",
        gitInfo: {
          branch: "feature/mobile",
          repositoryRoot: "C:\\Users\\melon\\repo",
          sha: "abc123",
          isDirty: true,
          remoteUrl: "https://secret@example.invalid/repo.git",
        },
        latestThreadSettings: {
          model: "gpt-5.2-codex",
          effort: "high",
          serviceTier: "priority",
          hiddenSetting: "not-exposed",
        },
        turnHistory: {
          history: {
            entitiesByKey: {
              active: { turnId: "turn-active", status: "inProgress", items: [] },
            },
            islands: [{ entries: [{ value: "active" }] }],
          },
        },
      },
    });

    expect(detail).toMatchObject({
      cwd: "C:\\Users\\melon\\repo\\",
      cwdGroupKey: "c:/users/melon/repo",
      cwdGroupLabel: "repo",
      gitInfo: {
        branch: "feature/mobile",
        repositoryRoot: "C:\\Users\\melon\\repo",
        sha: "abc123",
        isDirty: true,
      },
      settings: {
        model: "gpt-5.2-codex",
        effort: "high",
        serviceTier: "priority",
      },
      activeTurnId: "turn-active",
    });
    expect(JSON.stringify(detail)).not.toContain("remoteUrl");
    expect(JSON.stringify(detail)).not.toContain("hiddenSetting");
  });

  it("returns complete turn and per-file unified diffs through the dedicated view", () => {
    const fullTurnDiff = "diff --git a/a.ts b/a.ts\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n";
    const fullFileDiff = "diff --git a/b.ts b/b.ts\n--- a/b.ts\n+++ b/b.ts\n@@ -0,0 +1 @@\n+added\n";
    const diff = presentThreadDiff({
      threadId: "thread-diff",
      revision: 9,
      state: {
        turns: [{
          id: "turn-1",
          status: "completed",
          diff: fullTurnDiff,
          items: [{
            id: "file-item",
            type: "fileChange",
            changes: [{
              path: "src/b.ts",
              kind: "add",
              unifiedDiff: fullFileDiff,
            }],
          }],
        }],
      },
    });

    expect(diff).toEqual({
      threadId: "thread-diff",
      revision: 9,
      turns: [{ turnId: "turn-1", status: "completed", unifiedDiff: fullTurnDiff }],
      files: [{
        turnId: "turn-1",
        itemId: "file-item",
        path: "src/b.ts",
        kind: "add",
        unifiedDiff: fullFileDiff,
      }],
    });
  });
});
