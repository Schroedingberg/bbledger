# bbledger infra — Hetzner Cloud, via OpenTofu in CI

One CAX11 (ARM, 2 vCPU/4GB, ~3.79€/mo) runs the bot. Everything is applied
from GitHub Actions (`infra` workflow → run with `apply`); nothing needs a
local Terraform install. The VM is disposable: all household state lives in
the private data repo.

## The data repo (household state)

`household.ledger` **and `config.edn`** live in a separate private repo
(e.g. `bbledger-data`), not on the VM and not in this repo. cloud-init
clones it on first boot; a systemd path unit pushes after every recorded
entry. `destroy` + `apply` resumes from the clone. Config changes = commit
to the data repo, then restart the bot.

One-time:
1. Create the private repo with `household.ledger` (your rules + history)
   and a filled-in `config.edn` (start from `config.sample.edn`;
   `:ledger-file` stays `"/data/household.ledger"`).
2. Generate a dedicated keypair: `ssh-keygen -t ed25519 -f bbledger-deploy -N ""`.
3. Add `bbledger-deploy.pub` as a **deploy key with write access** on the
   data repo; the private half becomes the `DATA_DEPLOY_KEY` secret.

## One-time setup

1. **Hetzner**: create a **dedicated project** (bbledger must be the only
   thing in it — see below), then Security → API tokens → generate a
   **read/write token**.
2. **State**: there is none — no tfstate backend, no bucket. The dedicated
   project is the source of truth: `apply` first deletes the bbledger
   resources by name, then recreates everything (~2–3 min bot downtime,
   new IP; fine for rarely-changing infra). `plan` always shows a full
   create rather than a diff. Requires that nothing else ever lives in
   the project.

## GitHub secrets (repo → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `HCLOUD_TOKEN` | Hetzner Cloud API token (read/write) |
| `BBLEDGER_BOT_TOKEN` | bot token from @BotFather |
| `DATA_REPO` | SSH url, e.g. `git@github.com:Schroedingberg/bbledger-data.git` |
| `DATA_DEPLOY_KEY` | private half of the data-repo deploy key |
| `GHCR_PULL_TOKEN` | *(optional)* PAT with `read:packages` — only while the GHCR package is private |

SSH access: upload your public key to the Hetzner **project** (console →
Security → SSH keys) — every project key is installed on the server.

## Deploy

1. Actions → `infra` → *Run workflow* → `plan`; review the output.
2. Run again with `apply`. The VM boots, cloud-init installs docker + git,
   installs a pinned, checksum-verified cosign release, clones the data repo
   (ledger + config), and starts the bot, the summary timer, and the
   backup-push path unit. A tiny `bbledger-firewall` unit (reboot-safe)
   blocks containers from the cloud metadata endpoint, which serves the
   cloud-init secrets.
3. Send `12,30 Test` in the Telegram group; expect the ✓ — and the entry
   commit appearing in the data repo moments later.

Redeploying app versions never touches infra: merging to main releases a
new image, and the server's `bbledger-autodeploy.timer` pulls it and
restarts the bot within ~5 minutes (pull-based on purpose — CI holds no
server credentials). Before restarting, autodeploy verifies the image's
cosign signature (keyless, this repo's CI via GitHub OIDC) and runs a
one-shot `clojure -M:bot check` health check; if either fails, the old
bot keeps running and the timer retries. `bb deploy` / `bb restart` cover the impatient case.
`destroy` is safe for the ledger: every entry is pushed to the data repo,
and the next `apply` resumes from the clone.
