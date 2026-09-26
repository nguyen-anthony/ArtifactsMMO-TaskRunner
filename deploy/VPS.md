# VPS setup checklist

Recommended: **Hetzner Cloud CPX21 in Ashburn, VA** (2 vCPU / 4 GB, ~$10/mo), Ubuntu 24.04.
It sits next to Supabase `us-east-1`. DigitalOcean NYC or AWS Lightsail us-east-1 also work.

## 1. Server basics (as root, once)
```bash
adduser --disabled-password deploy
usermod -aG sudo deploy
mkdir -p /home/deploy/.ssh && cp ~/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
# Disable password + root SSH login
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl restart ssh
# Firewall: SSH + web only
ufw allow OpenSSH && ufw allow 80,443/tcp && ufw allow 443/udp && ufw enable
# Automatic security updates
apt update && apt install -y unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades
# Docker
curl -fsSL https://get.docker.com | sh && usermod -aG docker deploy
```

## 2. App (as `deploy`)
```bash
git clone https://github.com/<you>/ArtifactsMMO-TaskRunner.git ~/ArtifactsMMO-TaskRunner
cd ~/ArtifactsMMO-TaskRunner
cp .env.example .env && chmod 600 .env && nano .env
docker compose up -d --build
docker compose logs -f backend      # expect "Engine started for ..."
```
In `.env`:
- `DATABASE_*`: the Supabase **session pooler** (port 5432), same as local.
- `ADMIN_API_KEY`: a long random value (`openssl rand -base64 32`).
- Without a domain yet: leave `SITE_ADDRESS` unset and set `COOKIE_SECURE=false`, then browse to `http://<vps-ip>`.
  This is plain HTTP, so only use it briefly or over a VPN.

## 3. Add the subdomain (when ready)
1. DNS: `A` record `ammo.example.com` → VPS IP (and `AAAA` for IPv6 if you like).
2. `.env`: `SITE_ADDRESS=ammo.example.com` and `COOKIE_SECURE=true`.
3. `docker compose up -d`. Caddy fetches the Let's Encrypt certificate automatically.

## 4. CI deploys
Add `VPS_HOST`, `VPS_USER=deploy` and `VPS_SSH_KEY` as GitHub Actions secrets. Use a dedicated key pair:
`ssh-keygen -t ed25519 -f deploy_key`, append `deploy_key.pub` to `~deploy/.ssh/authorized_keys`, paste `deploy_key` into the secret.
Every push to `main` that passes tests then runs `git reset --hard origin/main && docker compose up -d --build` on the VPS.

## Operations
| Task | Command |
|---|---|
| Logs | `docker compose logs -f backend` |
| Restart | `docker compose restart backend` |
| Health | `curl -s localhost/api/health` (from the VPS) |
| Backup runtime data | `docker run --rm -v artifactsmmo-taskrunner_backend-data:/d -v $PWD:/b alpine tar czf /b/data.tgz -C /d .` |

The queue, configs and character settings live in Supabase (backed up by Supabase).
The `backend-data` volume only holds `known_loadouts.json`, the monster/potion data files and file logs.

Only one backend may run the engine at a time (Postgres advisory lock), so a redeploy
briefly waits ~15 s for the old container's lock to release before workers start.
