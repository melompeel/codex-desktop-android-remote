# Android Bridge API Contract

This document describes the additive `v1` contract used by the Android client. Existing
clients may continue to omit the new message fields and use `delivery: "auto"`.

## Authentication

Every route except `GET /v1/health` and `POST /v1/pair` requires:

- `Authorization: Bearer <device-token>`
- `X-Request-Id: <unique-id>`
- `X-Timestamp: <unix-seconds>`
- `X-Signature: <hex-hmac-sha256>`

The HMAC input is `METHOD`, the exact path including its encoded query string, timestamp,
request ID, and the SHA-256 hash of the raw body, joined with line feeds. Query order and
percent encoding must be identical in the request and signature input.

## Capabilities and models

`GET /v1/capabilities` returns `{ "capabilities": { ... } }`. `taskCreation` is true
when the known Desktop protocol operations and safe task materializer are available.
Attachments report `image` and `file`, a 10 MiB limit, and `queued: false`.

`GET /v1/models` returns `{ "models": [...] }`. Model rows use the Desktop app-server
fields `id`, `displayName`, `description`, `defaultReasoningEffort`,
`supportedReasoningEfforts`, `inputModalities`, `isDefault`, and `serviceTiers` when
present. Each supported effort uses `{ "reasoningEffort": "...", "description": "..." }`.

## Tasks

`GET /v1/tasks` preserves the legacy `{ "tasks": [...] }` response. Supplying any of
`query`, `archived`, `cursor`, or `limit` returns:

```json
{
  "tasks": [],
  "nextCursor": null
}
```

Task summaries and `GET /v1/tasks/:threadId` may include:

```json
{
  "cwd": "C:\\repo",
  "cwdGroupKey": "c:/repo",
  "cwdGroupLabel": "repo",
  "gitInfo": {
    "branch": "main",
    "repositoryRoot": "C:\\repo",
    "sha": "abc123",
    "isDirty": false
  },
  "settings": {
    "model": "model-id",
    "effort": "medium",
    "serviceTier": "priority"
  },
  "activeTurnId": "turn-id"
}
```

`PATCH /v1/tasks/:threadId/settings` accepts `{ "model": "...", "effort": "..." }`.
The pair is validated against the live model list and affects the next turn.

### Create a task

`POST /v1/tasks` accepts:

```json
{
  "cwd": "C:\\known-project",
  "prompt": "Implement the requested change",
  "model": "model-id",
  "reasoningEffort": "high",
  "idempotencyKey": "stable-operation-id"
}
```

The model and effort must occur in the live model list, and `cwd` must match a project
reported by the embedded app-server. The helper materializes and rolls back a bootstrap
turn, leaving a durable zero-turn task, then exits. It never receives the user's prompt.
After Codex Desktop becomes owner, the Bridge applies model settings and sends the real
prompt through `codex-ipc`.

A complete result is returned with status 201:

```json
{ "threadId": "...", "promptAccepted": true, "stage": "complete" }
```

If Desktop owner handoff, settings, or prompt submission fails after materialization,
status 202 returns the durable task as a partial result:

```json
{
  "threadId": "...",
  "promptAccepted": false,
  "stage": "owner",
  "error": "task-owner-handoff-timeout:..."
}
```

The stages are `owner`, `settings`, and `prompt`. Reusing the same device-scoped
idempotency key replays either complete or partial results and never creates another task.

`GET /v1/tasks/:threadId/diff` returns the complete, untruncated unified diff separately
from the compact timeline:

```json
{
  "diff": {
    "threadId": "...",
    "revision": 1,
    "turns": [
      { "turnId": "...", "status": "completed", "unifiedDiff": "..." }
    ],
    "files": [
      {
        "turnId": "...",
        "itemId": "...",
        "path": "src/app.ts",
        "kind": "update",
        "unifiedDiff": "..."
      }
    ]
  }
}
```

## Messages and queue

`POST /v1/tasks/:threadId/messages` accepts:

```json
{
  "text": "Continue",
  "delivery": "auto",
  "expectedTurnId": null,
  "expectedQueueHash": null,
  "idempotencyKey": "stable-operation-id",
  "attachmentIds": []
}
```

- `auto` keeps legacy behavior: start while idle, steer while active.
- `start` fails if a turn is active.
- `steer` requires the matching `expectedTurnId`.
- `queue` requires the latest `expectedQueueHash`; queued attachments are not supported.
- Text may be empty only when at least one attachment is present.
- Reusing an idempotency key in the same device/task scope returns the original result.

The response includes `delivery` and either `clientUserMessageId` or `queuedMessageId`.
A queue response also includes the predicted replacement `queueHash`.

`GET /v1/tasks/:threadId/queue` returns the authoritative mirrored queue:

```json
{
  "queue": {
    "threadId": "...",
    "hash": "sha256",
    "messages": [
      { "id": "...", "text": "...", "createdAt": 0 }
    ]
  }
}
```

Cancel with
`DELETE /v1/tasks/:threadId/queue/:messageId?expectedQueueHash=<hash>&idempotencyKey=<id>`.
The owner broadcasts the authoritative replacement queue after either mutation.

## Attachments

Upload raw bytes with a signed URL:

```text
POST /v1/attachments?name=<percent-encoded-name>&mimeType=<percent-encoded-mime>&idempotencyKey=<id>
Content-Type: <same MIME as signed query>
```

The response is `{ "attachment": { "attachmentId", "name", "mimeType", "size",
"kind", "expiresAt" } }`. Delete it with `DELETE /v1/attachments/:attachmentId`.

Supported images are PNG, JPEG, WebP, and GIF. Generic files are PDF and a strict
UTF-8 text/source whitelist. Executables, path components, Windows reserved names,
MIME/extension mismatches, invalid magic bytes, NUL-containing text, and files over
10 MiB are rejected. Attachments are device-owned and expire after one hour.

Images are delivered as Desktop `localImage` input. Generic files are delivered as the
Desktop-supported local `mention` input plus an `application` additional-context entry.

## Safety behavior

Desktop package and bundled CLI versions are reported as `verified` only when both match
the observed adapter. A mismatch enters `best-effort` mode: known IPC operations remain
available, parseable stream payloads continue to sync, and a rejected operation reports
its own protocol error without globally disabling writes. The helper is used only to
materialize a rolled-back zero-turn task; it is closed before the user's prompt is sent
and never substitutes for the Desktop owner.
