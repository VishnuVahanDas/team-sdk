# AGENTS.md — team-sdk (sync agent)

Instructions for AI coding agents working in this repository.

This repo is the **Vyom ERP sync agent**: a headless Java service on a Windows
PC next to the **Team Office Z200BW** attendance terminal. It reads punches
from the device over the LAN through the vendor's SBXPC SDK (via JNA) and
writes them into the web dashboard's PostgreSQL on the VPS. It opens no port.
The dashboard talks to it only through tables in the shared database.

It is usually checked out as the `sdk/` submodule of the `vyom-erp` parent
repo. On the office PC it is cloned on its own. Read [README.md](README.md)
first.

## Layout

```
src/main/java/org/ajeet/
  agent/SyncAgentMain.java   entry point: reads config, starts the loop
  agent/SyncAgent.java       loop: heartbeat + queued requests every tick, auto-sync every interval
  device/DeviceClient.java   connects, reads the general log, decodes punches, keeps the watermark
  device/Sbxpc.java          JNA mapping of SBXPCDLL64.dll
  device/Punch.java, DeviceConfig.java
  store/PunchStore.java      writes punches + users (idempotent inserts / upserts)
  store/AgentQueue.java      sync_requests queue + sync_agent heartbeat
  config/Env.java            env var / .env lookup
native/                      SBXPCDLL64.dll + SBPCCOMM64.dll (git-ignored, copied in by hand)
run-agent.bat                builds target/vyom-sync-agent.jar if missing, then runs it
```

## Things that have broken before (read before changing)

- **Never wipe the device log.** Don't call `EmptyGeneralLogData` or any other
  clear or delete call on the device. Sync uses a high-water mark (newest
  synced punch time in `device-sync-state.txt`), so a failed DB write never
  loses punches.
- **Use `ReadAllGLogData` for full reads.** `ReadGeneralLogData` returns only
  records not yet marked read, so it misses history. Full backfills ("Sync all
  history" on the dashboard) must use `ReadAllGLogData`.
- **The verify mode is packed:** method in the low byte, check-in/out status in
  the next byte, anti-passback in the upper 16 bits (e.g. 407 = Fingerprint ·
  Check-out). `verify_label` is part of the `punches` unique key, so **changing
  how labels are written duplicates every punch on the next re-sync** unless
  you also add a one-time migration that relabels existing rows, as the
  verify-mode fix did.
- **Time zones:** punch times are device-local wall-clock time
  (`LocalDateTime` → `TIMESTAMP`, no zone). The queue connection is pinned to
  UTC (`SET TIME ZONE 'UTC'` in `AgentQueue`), because the dashboard writes
  `requested_at` with `now()` in UTC. Without the pin, the PC's IST zone made
  every "Sync device" request expire immediately (TTL is 120 s). Don't remove
  it, and don't mix the two time conventions.
- **JNA mapping:** `Sbxpc` signatures mirror the vendor's C# sample
  (`SBXPCDLL.cs`). The DLL exports carry a leading `_`, which a FunctionMapper
  adds. The DLLs are 64-bit only, so a 64-bit JDK is required.
- The loop must survive errors: record them in the heartbeat and carry on. A
  device that is briefly offline should recover on the next tick.

## Shared database contract with the web dashboard

This agent writes `punches` and `users`, claims `sync_requests` (with
`FOR UPDATE SKIP LOCKED`) and refreshes the single `sync_agent` heartbeat row.
The `erp-dashboard` repo creates the same four tables. Both sides use
`CREATE TABLE IF NOT EXISTS`, so either can start first.
**Any schema change here needs the matching change in `erp-dashboard`**
(`AttendanceStore`, `SyncRequests`), and the "Database contract" sections in
both READMEs must stay in step. Schema changes must be additive and safe to
re-run.

## Build, run, check

Needs 64-bit Windows, a 64-bit JDK 24+, and the two DLLs in `native/`.
Maven isn't needed; use the wrapper.

```bat
copy .env.example .env            :: once; fill PG_* and DEVICE_PASSWORD; never commit
mvnw.cmd -q -DskipTests package   :: builds target\vyom-sync-agent.jar
run-agent.bat                     :: runs it (only builds if the jar is missing)
```

`run-agent.bat` doesn't rebuild an existing jar, so rebuild after code changes.

There is no automated test suite. Before committing, the package build must
succeed. If you can't reach a real device, say so and describe what you
checked rather than claiming it works. On a dev machine without the device,
point the agent at a local `docker compose up -d db` from the dashboard repo to
check the database side.

## Security rules (hard requirements)

- **Never commit `.env`** (it holds the VPS database password and the device
  comm key) or `device-sync-state.txt`.
- **Never commit the vendor DLLs in `native/`** or the vendor SDK samples.
  They are proprietary.
- Don't commit `target/`.
- Don't log the database password or the device comm key.

## Git workflow

- Commit messages follow Conventional Commits with a scope, as in the history:
  `feat(agent): ...`, `fix(agent): ...`. The body explains *why*, including
  device or SDK quirks you discovered.
- Use `feat/<topic>` branches for non-trivial changes and merge them into
  `main`.
- No force-pushes or history rewrites without explicit approval from the user.
- **After anything lands on `main`, update the pointer in the `vyom-erp`
  parent repo:** `git add sdk` there, commit as `chore: bump sdk (<summary>)`
  and push. Push this repo first. See the parent's AGENTS.md.

## Keep the docs current

Update the docs in the same commit as the change they describe:

- **README.md:** setup steps, `.env` keys (also add them to `.env.example`),
  sync behaviour, and the database contract.
- **AGENTS.md (this file):** layout changes, new conventions, and any device,
  SDK or database quirk you discover. Add it to "Things that have broken
  before".
- If the change affects the shared tables, also update the `erp-dashboard`
  README/AGENTS.md and the `vyom-erp` README/AGENTS.md.
