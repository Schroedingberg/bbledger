# bbledger infra — Oracle Cloud Always Free, via OpenTofu in CI

Everything is applied from GitHub Actions (`infra` workflow → run with
`apply`). Nothing here needs a local Terraform install.

## Known Always-Free gotchas (read first)

- **"Out of host capacity"**: free-tier A1 instances are scarce in popular
  regions. Retry later, set `availability_domain_index` to 1 or 2, or —
  the reliable fix — upgrade the account to **Pay-As-You-Go** (still 0€
  while you only use Always-Free shapes).
- **Idle reclamation**: Oracle reclaims Always-Free A1 instances with
  sustained low utilization. A chat bot is idle by nature. PAYG upgrade
  also removes this rule.

## One-time setup (Oracle console)

1. Create an Oracle Cloud account; note your **home region** (e.g.
   `eu-frankfurt-1`) and **tenancy OCID**.
2. Your user → API keys → *Add API key*: download the private key PEM,
   note the **fingerprint** and **user OCID**.
3. Object Storage → create bucket `bbledger-tfstate`. Note the
   **namespace** (shown on the bucket page).
4. Your user → Customer secret keys → *Generate*: this is the S3-compatible
   **access/secret key pair** for the state backend.
5. (Simplest) use the tenancy root compartment: compartment OCID = tenancy
   OCID. Or create a `bbledger` compartment and use its OCID.

## GitHub secrets (repo → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `OCI_TENANCY_OCID` | tenancy OCID |
| `OCI_USER_OCID` | user OCID |
| `OCI_FINGERPRINT` | API key fingerprint |
| `OCI_PRIVATE_KEY` | full PEM content of the API private key |
| `OCI_REGION` | e.g. `eu-frankfurt-1` |
| `OCI_COMPARTMENT_OCID` | compartment (or tenancy) OCID |
| `OCI_NAMESPACE` | Object Storage namespace |
| `OCI_STATE_BUCKET` | `bbledger-tfstate` |
| `OCI_STATE_ACCESS_KEY` | customer secret key — access part |
| `OCI_STATE_SECRET_KEY` | customer secret key — secret part |
| `BBLEDGER_BOT_TOKEN` | bot token from @BotFather |
| `BBLEDGER_CONFIG_EDN` | full content of your filled-in config.edn |
| `GHCR_PULL_TOKEN` | GitHub PAT with `read:packages` (VM pulls the image) |
| `SSH_PUBLIC_KEY` | your ssh key, for debugging the VM (optional) |

## Deploy

1. Actions → `infra` → *Run workflow* → `plan`; review the output.
2. Run again with `apply`. The VM boots, cloud-init installs docker + git,
   writes `/srv/bbledger/data/config.edn`, initializes the data git repo,
   and starts `bbledger-bot.service` + the summary timer.
3. `household.ledger` starts **empty** — copy your real rules/history in
   once (the public IP is in the apply output):
   ```sh
   scp household.ledger ubuntu@<ip>:/srv/bbledger/data/
   ssh ubuntu@<ip> 'cd /srv/bbledger/data && git add -A && git commit -m "real ledger"'
   ```
4. Send `12,30 Test` in the Telegram group; expect the ✓.

Redeploying app versions never touches infra: CI pushes a new image and
`ssh ubuntu@<ip> sudo systemctl restart bbledger-bot` picks it up.
`destroy` tears everything down; the ledger data dies with it, which is why
the bot's git history should be pushed somewhere or backed up if you care
(a private GitHub data repo + a cron `git push` is the natural extension).
