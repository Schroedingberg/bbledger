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

1. **Hetzner**: Cloud Console → your project → Security → API tokens →
   generate a **read/write token**.
2. **State bucket** (any S3-compatible store; Backblaze B2's free tier
   works well): create bucket `bbledger-tfstate` (private) and an
   application key scoped to it. Note the S3 endpoint shown with the
   bucket, e.g. `https://s3.eu-central-003.backblazeb2.com`, whose region
   is the middle part (`eu-central-003`).

## GitHub secrets (repo → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `HCLOUD_TOKEN` | Hetzner Cloud API token (read/write) |
| `STATE_BUCKET` | `bbledger-tfstate` |
| `STATE_ENDPOINT` | e.g. `https://s3.eu-central-003.backblazeb2.com` |
| `STATE_REGION` | e.g. `eu-central-003` |
| `STATE_ACCESS_KEY` | application key id |
| `STATE_SECRET_KEY` | application key secret |
| `BBLEDGER_BOT_TOKEN` | bot token from @BotFather |
| `DATA_REPO` | SSH url, e.g. `git@github.com:Schroedingberg/bbledger-data.git` |
| `DATA_DEPLOY_KEY` | private half of the data-repo deploy key |
| `GHCR_PULL_TOKEN` | GitHub PAT with `read:packages` (VM pulls the image) |
| `SSH_PUBLIC_KEY` | your ssh key, for debugging the VM (optional) |

## Deploy

1. Actions → `infra` → *Run workflow* → `plan`; review the output.
2. Run again with `apply`. The VM boots, cloud-init installs docker + git,
   clones the data repo (ledger + config), and starts the bot, the summary
   timer, and the backup-push path unit.
3. Send `12,30 Test` in the Telegram group; expect the ✓ — and the entry
   commit appearing in the data repo moments later.

Redeploying app versions never touches infra: CI pushes a new image and
`ssh root@<ip> systemctl restart bbledger-bot` picks it up (IP is in the
apply output). `destroy` is safe for the ledger: every entry is pushed to
the data repo, and the next `apply` resumes from the clone.
