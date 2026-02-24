# MailVault

Minimal UI to browse and read emails indexed in SQLite.

## Run

```bash
cd backend
MAILVAULT_INDEX_ROOT_DIR=./data/emails \\
MAILVAULT_STORAGE_DIR=./data/storage \\
./gradlew bootRun
```

The application starts at `http://localhost:8080`.

## Docker Compose (dev)

Example:

```bash
docker compose -f docker/docker-compose.dev.yml up --build
```

## Use the UI

- Authentication:
  - On first startup, `/` opens an initial setup page to create the first account.
  - After setup, `/login` is used to authenticate and all routes/resources require an authenticated session, including `/`, `/messages/{id}`, `/assets/**`, and attachment downloads (`/api/attachments/{attachmentId}/download`).
  - Exception: health endpoint `GET /api/health` is public (no auth).
- Historical inbox (list and search): `GET /`
- Administrative page: `GET /admin`
- List/search API: `GET /api/messages?query=&year=&hasAttachments=&hasHtml=&hasFrozenImages=&page=&size=`
- Usage statistics: `GET /api/stats`
- Change password: `PUT /api/auth/password`
- Maintenance cleanup: `POST /api/maintenance/cleanup`
- SQLite compaction: `POST /api/maintenance/vacuum`
- Destructive reset of indexed data + vacuum: `POST /api/maintenance/reset-indexed-data`
- Message detail: `GET /messages/{id}`
- Detail navigation: `GET /api/messages/{id}/prev` and `GET /api/messages/{id}/next`
- Manual reindex from detail: **Reindex** button (calls `POST /api/index`)
- Sanitized HTML rendering: `GET /api/messages/{id}/render`
- Safe external navigation (links): `GET /go?url=...`
- Inline CID: `GET /api/messages/{id}/cid/{cid}`
- Attachments list: `GET /api/messages/{id}/attachments`
- Attachment download: `GET /api/attachments/{attachmentId}/download`
- Remote image freeze: `POST /api/messages/{id}/freeze-assets`
- Freeze pending for current list query/page: `POST /api/messages/freeze-pending?query=&year=&hasAttachments=&hasHtml=&hasFrozenImages=&page=&size=`
- Toggle freeze ignore per message: `PUT /api/messages/{id}/freeze-ignored?ignored=true|false`
- Serve frozen assets: `GET /assets/{messageId}/{filename}`

In `GET /api/messages/{id}`, in addition to basic metadata, the response also includes:
- `attachmentsCount`
- `frozenAssetsCount`
- `assetsFailedCount`
- `securitySkippedCount` (assets skipped by security guard)
- `freezeIgnored`
- `freezeLastReason` (last freeze outcome reason per message)
- `messageSizeBytes`
- `filePath` (plain text for copy)

## Safe HTML rendering

- Raw email HTML (`html_raw`) is rewritten to:
  - `<a href>` links -> `/go?url=...`
  - `cid:` images -> `/api/messages/{id}/cid/{cid}`
  - remote `http/https` images -> `/static/remote-image-blocked.svg` with `data-original-src`
- Final HTML is sanitized with OWASP Java HTML Sanitizer and cached in `html_sanitized`.
- Active/dangerous elements (e.g., `script`, `iframe`, `form`, `on*` handlers, `javascript:`) are blocked.
- Remote images only appear after freeze; before that, they are replaced by a local placeholder.

## Quick flow

1. Configure `MAILVAULT_INDEX_ROOT_DIR` pointing to the mapped directory containing `.eml` files.
2. (Optional) Configure `MAILVAULT_STORAGE_DIR` for the attachments volume.
3. (Optional) Configure limits:
   - `MAILVAULT_MAX_ASSETS_PER_MESSAGE` (default `50`)
   - `MAILVAULT_MAX_ASSET_BYTES` (default `10485760`)
   - `MAILVAULT_TOTAL_MAX_BYTES_PER_MESSAGE` (default `52428800`)
   - `MAILVAULT_ASSET_CONNECT_TIMEOUT_SECONDS` (default `5`)
   - `MAILVAULT_ASSET_READ_TIMEOUT_SECONDS` (default `10`)
   - `MAILVAULT_ASSET_ALLOWED_PORTS` (default `80,443`)
   - `MAILVAULT_FREEZE_ON_INDEX` (default `false`)
   - `MAILVAULT_FREEZE_ON_INDEX_CONCURRENCY` (default `2`)
4. Open `http://localhost:8080/` and complete initial setup (create username/password and choose language).
5. After setup, authenticate on `/login` and use the UI normally.
6. Click an item to open `http://localhost:8080/messages/{id}` and read `text/plain`/HTML.
7. In detail, use **Freeze images** to download remote images with limits and SSRF protection.
8. In detail, use **Previous/Next** or shortcuts `k`/`j`; use `g` to go back to list while preserving filters.
9. Open `http://localhost:8080/admin` to change password, change UI language, and run index/maintenance/reset operations.

## Automatic freeze on index

- With `MAILVAULT_FREEZE_ON_INDEX=true`, new/updated messages may trigger remote-image freeze automatically at the end of indexing.
- The process is best-effort: network/SSRF failures do not fail indexing.
- Messages marked with `freezeIgnored=true` are skipped by auto-freeze and by the UI action **Freeze pending**.
- Limits:
  - `MAILVAULT_FREEZE_ON_INDEX_CONCURRENCY`: automatic freeze parallelism.
- To avoid repeated work, messages that already have `DOWNLOADED` assets are skipped during auto-freeze.

## Recent migrations

- `V5__html.sql`: adds `html_raw` and `html_sanitized` to `message_bodies`
- `V6__attachments.sql`: creates `attachments` table for metadata and storage path
- `V7__assets.sql`: creates `assets` table for remote-image freeze and local cache
- `V8__message_date_epoch.sql`: adds `date_epoch` in `messages` for consistent descending chronological ordering
- `V9__fts_rebuild_with_body.sql`: rebuilds `messages_fts` with `subject`, `from_raw`, and `text_plain`; adds sync triggers on `messages` and `message_bodies`
- `V10__html_text_fts.sql`: adds `html_text` to `message_bodies` and rebuilds `messages_fts` to index extracted HTML text as well
- `V11__message_display_headers.sql`: adds `subject_display`, `from_display`, `from_email`, and `from_name` to `messages` to show decoded RFC 2047 headers
- `V12__messages_freeze_ignored.sql`: adds `freeze_ignored` to `messages` to allow marking/unmarking messages that should be ignored by freeze routines
- `V13__assets_security_blocked.sql`: adds `security_blocked` to `assets` to distinguish security-skipped assets from other skipped reasons
- `V14__messages_freeze_last_reason.sql`: adds `freeze_last_reason` to `messages` to persist the latest freeze outcome reason shown in UI

## Search and filters (`GET /api/messages`)

- `query`: uses FTS (`messages_fts`) on `subject`, `from_raw`, `text_plain`, and `html_text`
- `year`: filters by year derived from `date_epoch`
- `hasAttachments`: `true/false` for existence in `attachments`
- `hasHtml`: `true/false` for non-empty `message_bodies.html_raw`
- `hasFrozenImages`: `true/false` for existence of `assets` with `status = DOWNLOADED`
- Ordering:
  - with `query`: relevance `bm25(messages_fts)` then date desc
  - without `query`: date desc
- Home (`/`) exposes all these filters in the UI (year + attachments/html/frozen images), with URL state and active filter chips.

## Minimal observability (`GET /api/stats`)

- Returns:
  - `totalMessages`
  - `totalWithHtml`
  - `totalAttachments`
  - `totalAssetsDownloaded`
  - `totalAssetsFailed`
  - `storageBytesAttachments`
  - `storageBytesAssets`
  - `lastIndexAt`
- On index completion, indexer records in `app_meta`:
  - `lastIndexAt`
  - `lastIndexDurationMs`

## Maintenance

- `POST /api/maintenance/cleanup`:
  - removes orphan files in `storage/attachments` and `storage/assets` (no DB reference)
  - removes `messages` rows whose `.eml` file no longer exists (cascade clears related metadata)
- `POST /api/maintenance/vacuum`:
  - runs SQLite `VACUUM` to reclaim space
  - may be expensive and temporarily block DB access
- `POST /api/maintenance/reset-indexed-data`:
  - destructive operation for indexed data only
  - deletes rows from `messages`, `message_bodies`, `attachments`, and `assets`
  - removes stored files under `storage/attachments` and `storage/assets`
  - preserves `app_meta` entries (including auth credentials and UI settings)
  - runs `VACUUM` at the end
