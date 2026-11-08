# Home PC hosting (Windows + Docker Desktop + Tailscale)

Runs the production stack (backend + Caddy web UI) on an always-on Windows PC, reachable from anywhere
through Tailscale (private VPN, automatic HTTPS, no router port forwarding). All state that matters
(queue, configs, character settings) lives in Supabase, so an outage only pauses the workers.

```
phone/laptop ──Tailscale (HTTPS)──► tailscale serve ──► 127.0.0.1:8088 ──► web (Caddy) ──/api──► backend:8080 ──► Supabase + game API
```

Run all commands from the repo root in PowerShell. If `docker` isn't on PATH, open a new terminal after
Docker Desktop starts, or use `$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe`.

## 1. Docker Desktop settings

Settings → **General**:
- ✅ *Start Docker Desktop when you sign in to your computer*
- ✅ *Use the WSL 2 based engine*

Settings → **Resources** (if shown): at least 4 GB memory. The backend uses ~70% of what its container gets.

Containers use `restart: unless-stopped`, so they come back whenever Docker Desktop starts.

## 2. Configure `.env`

```powershell
Copy-Item .env.example .env   # skip if .env already exists (it is git-ignored)
notepad .env
```

| Key | Value |
|---|---|
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` / `DATABASE_SCHEMA` | Supabase **session pooler** (port 5432), same as local dev |
| `ARTIFACTS_TOKEN` | Your game token |
| `ADMIN_API_KEY` | Long random value, what you type into the web login (generate below) |
| `COOKIE_SECURE` | Leave unset (= `true`); Tailscale serves HTTPS |
| `SITE_ADDRESS` | **Leave unset**. Caddy then serves plain HTTP inside the box and Tailscale adds HTTPS |

Generate an admin key:
```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

## 3. Start the stack

Only one engine may run per database. **Stop any `.\gradlew.bat :server:run` first**; a second
engine just waits on the Postgres lock, but don't rely on that.

```powershell
docker compose -f docker-compose.yml -f docker-compose.home.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.home.yml ps          # both "running", backend "healthy" after ~1 min
docker compose -f docker-compose.yml -f docker-compose.home.yml logs -f backend   # expect "Engine started for ..."
Invoke-RestMethod http://127.0.0.1:8088/api/health                          # status ok, database ok, engine True
```

Open <http://localhost:8088> on the PC and log in with `ADMIN_API_KEY`.
`docker-compose.home.yml` publishes the UI on **localhost only** (port 8088), so nothing on your LAN or
the internet can reach it directly. Ports 80/443 stay free.

## 4. Remote access with Tailscale

1. Install Tailscale for Windows (<https://tailscale.com/download>) and sign in.
2. Tray icon → **Preferences** → ✅ *Run unattended* (keeps it connected while signed out or after reboots)
   and ✅ *Launch Tailscale at startup*.
3. Admin console (<https://login.tailscale.com/admin>):
   - **DNS** → enable **MagicDNS** and **HTTPS Certificates**.
   - **Machines** → this PC → **⋯ → Disable key expiry** (otherwise it drops off after 180 days, maybe mid-trip).
4. In an **admin** PowerShell:
   ```powershell
   tailscale serve --bg 8088
   tailscale serve status        # shows https://<pc-name>.<tailnet>.ts.net
   ```
   The serve config persists across reboots.
5. Install Tailscale on your phone and laptop with the same account. Open
   `https://<pc-name>.<tailnet>.ts.net` and log in.

Only devices in your tailnet can reach it. For a public URL without the VPN client, use
`tailscale funnel --bg 8088` instead of `serve`; anyone can then reach the login page, so keep
`ADMIN_API_KEY` long (logins are throttled).

## 5. Keep the PC up while you're away

| Risk | Fix |
|---|---|
| Sleep or hibernate | Admin PowerShell: `powercfg /change standby-timeout-ac 0; powercfg /change hibernate-timeout-ac 0; powercfg /hibernate off` |
| Windows Update reboot | Settings → Windows Update → **Pause updates** for the trip length. Install pending updates *before* you leave. |
| Reboot leaves it at the lock screen (Docker Desktop needs a signed-in user) | Enable auto sign-in with Sysinternals **Autologon** (<https://learn.microsoft.com/sysinternals/downloads/autologon>). If you sign in with Windows Hello, first turn off Settings → Accounts → Sign-in options → *For improved security, only allow Windows Hello sign-in*. |
| Power cut | BIOS/UEFI: **Restore on AC power loss = Power On** (name varies by motherboard) |
| Need to fix something remotely | Windows 11 Home can't host Remote Desktop. Install **Chrome Remote Desktop** (<https://remotedesktop.google.com/access>), which also works through reboots. |

Test before leaving: reboot the PC and confirm, without touching it, that it signs in, Docker and the
containers start, and the Tailscale URL works from your phone on cellular data.

## 6. Get alerted when it goes down (Healthchecks.io, free)

UptimeRobot-style checks can't reach a Tailscale-only URL, so the PC pings out instead and
Healthchecks.io alerts you when the pings stop.

1. Create a check at <https://healthchecks.io>: period 5 min, grace 10 min. Add email or phone-app notifications. Copy the ping URL.
2. Admin PowerShell (replace the URL):
   ```powershell
   $script = "$PWD\deploy\home-health.ps1"
   $url = 'https://hc-ping.com/<your-uuid>'
   schtasks /Create /F /TN "TaskRunner health ping" /SC MINUTE /MO 5 /RU SYSTEM `
     /TR "powershell -NoProfile -ExecutionPolicy Bypass -File `"$script`" -PingUrl $url"
   schtasks /Run /TN "TaskRunner health ping"     # check the Healthchecks.io dashboard turns green
   ```
   The script reports failure unless the database is `ok` and the engine is running.

## Operations

Set this alias in the session to shorten commands:
`function dc { docker compose -f docker-compose.yml -f docker-compose.home.yml @args }`

| Task | Command |
|---|---|
| Deploy latest code | `git pull; dc up -d --build` (engine pauses ~15 s for the lock handoff) |
| Logs | `dc logs -f backend` |
| Restart backend | `dc restart backend` |
| Stop everything (e.g. to run `:server:run` for dev) | `dc stop` — and `dc start` afterwards |
| Back up runtime data | `docker run --rm -v artifactsmmo-taskrunner_backend-data:/d -v ${PWD}:/b alpine tar czf /b/data.tgz -C /d .` |

The `backend-data` volume keeps `known_loadouts.json`, the monster/potion data files and file logs. They're
seeded from the repo **only on first start**, so repo changes to those files don't reach the container;
see "Seasonal map refresh" in `FOLLOW_UPS.md` for refreshing them.

Line endings: `.gitattributes` forces LF for `gradlew` and `*.sh`, which the Linux images execute.
If a build fails with `/bin/sh^M` or `gradlew: not found`, re-checkout those files:
`Remove-Item gradlew, deploy\entrypoint.sh; git checkout -- gradlew deploy/entrypoint.sh`.
