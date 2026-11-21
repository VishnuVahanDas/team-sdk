# Vyom ERP — Sync Agent (SDK)

Headless Windows service that pulls punches from the **Team Office Z200BW**
terminal over the LAN (vendor SBXPC SDK via JNA) and writes them into the web
dashboard's PostgreSQL. It opens no port: the dashboard's **Sync device**
button reaches it through a request table in the same database.

```
Z200BW ──LAN──> this agent (Windows PC) ──JDBC──> PostgreSQL on the VPS <── web dashboard
```

- Auto-syncs every `SYNC_INTERVAL_SECONDS` (default 120, min 15) and refreshes
  a heartbeat every 3 s (the dashboard shows it as online/offline).
- Picks up **Sync device** requests within ~3 s.
- Dedup: newest synced punch time is kept in `device-sync-state.txt`; the DB
  also ignores duplicates, so re-syncs are safe. The device log is never wiped.
- First run syncs today's punches only.

```
sdk/
├── run-agent.bat        builds if needed, then starts the agent
├── .env.example         copy to .env
├── native/              SBXPCDLL64.dll + SBPCCOMM64.dll (not in git - copy in)
└── src/main/java/org/ajeet/
    ├── agent/       SyncAgentMain (entry point), SyncAgent (loop)
    ├── device/      DeviceClient, Sbxpc (JNA DLL mapping), Punch, DeviceConfig
    ├── store/       PunchStore (punches/users), AgentQueue (requests/heartbeat)
    └── config/Env.java
```

## Setup on the Windows PC

Needs **64-bit Windows** and a **64-bit JDK 24+** (e.g. Temurin). Maven isn't
needed — `mvnw.cmd` downloads it.

1. Copy (or `git clone`) only this folder to the PC.
2. Put `SBXPCDLL64.dll` and `SBPCCOMM64.dll` (from the vendor SDK) in `native\`.
3. `copy .env.example .env` and fill in `PG_*` (the VPS database) and
   `DEVICE_PASSWORD` (the comm key set on the device).
4. Double-click `run-agent.bat` (or run it from a terminal). The first start
   builds `target\vyom-sync-agent.jar`.

To start it automatically, add `run-agent.bat` to Task Scheduler ("At startup",
"Run whether user is logged on or not"), or wrap it with a service tool like NSSM.

After code changes, rebuild with `mvnw.cmd -q -DskipTests package`.

## Configuration (`.env`)

| Key | Default | |
| --- | ------- | - |
| `PG_URL`, `PG_USER`, `PG_PASSWORD` | — | web dashboard's database (required) |
| `DEVICE_PASSWORD` | — | device comm key (required) |
| `DEVICE_IP` / `DEVICE_PORT` / `DEVICE_MACHINE` | `192.168.31.120` / `5005` / `1` | |
| `DEVICE_DLL_DIR` | `native` | folder with the two DLLs |
| `SYNC_INTERVAL_SECONDS` | `120` | |

Values are read verbatim — no `# comments` on the same line.

## Database contract

The agent writes `punches` and `users`, claims rows from `sync_requests` and
keeps the `sync_agent` heartbeat. It creates those tables if missing; the
schema must match the web dashboard repo (see its README).
