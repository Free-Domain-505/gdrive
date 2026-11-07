# Xorvila GDrive Backup — Fabric 1.20.1

A server-side Fabric mod for Minecraft 1.20.1. It backs up the active world save to Google Drive on a schedule or on demand, and includes an authenticated live web dashboard.

## Compatibility

- Minecraft: **1.20.1**
- Fabric Loader: **0.14.21 or newer** (build target 0.16.10)
- Java: **17 or newer**
- Fabric API: compatible 1.20.1 release
- Fabric only; not Forge or NeoForge

For a dedicated server, install the mod on the actual server hosting the world. Players joining do not need the mod. For single-player, install it in the owner's game instance because its integrated server owns the save.

## Dashboard and persistent login

The public root URL redirects to the dashboard:

```text
https://xdrive.xorvilahub.indevs.in
https://xdrive.xorvilahub.indevs.in/dashboard
```

The dashboard is served locally on `127.0.0.1:8765` and should be exposed through the configured Cloudflare Tunnel. Its responsive interface supports desktop and mobile screens, live status/settings updates, Google Drive integration, and an event console.

On first launch, the mod generates a strong password and stores it in `config/gdrivebackup.json` as `dashboardPassword`. You can replace it there or through the dashboard's integration settings (minimum 12 characters). The `dashboardSessionSecret` is generated once and stored in the same protected config. It signs an HTTP-only browser cookie with a 30-day lifetime, so refreshing the page or restarting the Minecraft/server process does not sign you out. Use **Sign out** to end the browser session. Changing the dashboard password invalidates older sessions; the current dashboard session is renewed.

Do not delete or regenerate `dashboardSessionSecret` unless you intend to invalidate existing browser sessions. The config contains sensitive OAuth and tunnel credentials; keep it private.

## Console archive and command access

The dashboard event console stores audit events in the embedded H2 database at `config/gdrivebackup.mv.db`.

- The newest **50 events** stay in the local database and appear in the dashboard.
- When more than 50 events exist and Google Drive is connected, older events are exported in JSON archives to the configured `driveFolder`.
- Local rows are deleted **only after** the corresponding Drive archive upload succeeds (or an existing archive with the same event-ID range is verified). If Drive is disconnected or upload fails, rows remain in the database and are retried later.
- Archive files are named `console-events-<first-id>-<last-id>.json`.

The dashboard console accepts only `/gdrivebackup` commands, such as:

```text
/gdrivebackup status
/gdrivebackup now
/gdrivebackup connect
/gdrivebackup interval 30
/gdrivebackup retention 10
/gdrivebackup folder Minecraft Backups
/gdrivebackup enable
/gdrivebackup disable
```

It does not expose arbitrary server commands. Commands run on the Minecraft server thread and require an active world/server. Minecraft's normal server log files (for example `logs/latest.log`) are separate from this dashboard audit-event stream and are not included in these JSON archives.

## Cloudflare Tunnel and Google OAuth

Cloudflare Zero Trust must route the public hostname to:

- Hostname: `xdrive.xorvilahub.indevs.in`
- Service: `http://127.0.0.1:8765`

The mod can start `cloudflared` itself when `autoStartCloudflared` is true. Put a newly rotated tunnel token in `cloudflaredToken`; never commit or share it.

The Google OAuth Web application must register this exact redirect URI under **Authorized redirect URIs**:

```text
https://xdrive.xorvilahub.indevs.in/oauth2/callback
```

Configure `clientId` and `clientSecret` in `config/gdrivebackup.json`, then use `/gdrivebackup connect` or the dashboard's **Connect account** action. Google requires this one-time client setup; Cloudflare cannot bypass redirect-URI registration.

Google Cloud OAuth clients: https://console.cloud.google.com/auth/clients

## Default configuration

The mod creates `config/gdrivebackup.json` on first startup. Relevant settings:

- `enabled`: enable/disable scheduled backups
- `intervalMinutes`: 1–43,200 minutes (default 360)
- `retention`: keep 1–100 full Minecraft instance backups in Drive (default 10)
- `driveFolder`: Drive folder used for full-instance backup ZIPs and console JSON archives
- `dashboardPassword`: generated on first run; may be set manually (12–128 characters)
- `dashboardSessionSecret`: generated session-signing key; keep it unchanged to preserve sessions across restarts
- `publicBaseUrl`, `callbackPath`, `oauthPort`: public OAuth callback and local listener settings
- `autoStartCloudflared`, `cloudflaredExecutable`, `cloudflaredToken`: optional managed tunnel connector settings

## Commands

- `/gdrivebackup now`
- `/gdrivebackup connect`
- `/gdrivebackup status`
- `/gdrivebackup dashboard`
- `/gdrivebackup enable`
- `/gdrivebackup disable`
- `/gdrivebackup interval <minutes>`
- `/gdrivebackup intervalhours <hours>` (legacy)
- `/gdrivebackup retention <count>`
- `/gdrivebackup folder <name>`
- `/gdrivebackup tunnel status`
- `/gdrivebackup disconnect`

Dedicated-server commands require operator permission level 2 or higher.

## Backup scope and limitations

- Creates a ZIP of the active world save directory, not the complete server installation, `mods` folder, external databases, or every world.
- Restore is not currently implemented.
- A temporary local ZIP is removed after upload/failure; world saving is flushed and briefly paused during archive creation.
- The console archive is the mod's dashboard audit stream, not a capture of all raw Minecraft log output.
- Android environments must provide a compatible `cloudflared` executable if automatic tunnel startup is enabled. Alternatively, manage the tunnel externally and set `autoStartCloudflared` to false.

## Build

Requires JDK 17 and Gradle 8.8:

```sh
gradle build
```

The mod JAR is generated in `build/libs/`. GitHub Actions is configured to build the JAR using Java 17 and Gradle 8.8.


## Full-instance backup scope (v1.5.0)

Backups now archive the complete Fabric game directory selected by Fabric Loader, not only the active world save. On a dedicated server this is the server working/game directory; in single-player it is the selected Minecraft game directory. This includes the world save and its Nether/End dimensions, `mods/`, `config/`, `logs/`, `crash-reports/`, `plugins/` if present, datapacks, `server.properties`, and other files/directories under that root. Empty directories are retained. Symbolic links and Minecraft's live `session.lock` are skipped for safety.

**Important:** a full game-directory archive can be much larger than a world-only backup. In single-player, the game directory may also contain launcher assets, libraries, resource packs, shader packs, and multiple saves. The archive also includes configuration files, which may contain credentials or private server settings; keep the connected Google Drive account secure. Restore by stopping Minecraft first, extracting the archive into the same game/server directory, and then starting the same Minecraft/Fabric installation.
