# bbledger

Tiny hledger-subset for splitting household costs by income ratio, plus a
Telegram bot for day-to-day recording. The ledger file is the database:
plain text, hledger-compatible, one git commit per bot entry.

## CLI (babashka)

```sh
bb ledger bal Verrechnung --auto   # settlement: negative = owes, positive = is owed
bb ledger is                       # income statement
bb ledger bs                       # balance sheet
bb test                            # full suite (needs hledger for conformance)
```

## Telegram bot

In the dedicated group, any message starting with an amount **with decimals**
records an expense, paid by the sender:

```
Alice: 45.60 Router               ->  2026-07-09 Router
                                        Assets:Alice:Cash  €-45.60
                                        Expenses:Sonstiges  €45.60

Bob: 12,30 Drogerie #Haushalt:Drogerie
                                  ->  2026-07-09 Drogerie
                                        Assets:Bob:Cash  €-12.30
                                        Expenses:Haushalt:Drogerie  €12.30

/bal        settlement (who owes whom)
/summary    month-to-date by category
/undo       revert the last recorded expense (git revert)
/help       usage
```

The sender is the payer: their `Assets:<Person>:Cash` account (from the
config `:users` mapping) funds the expense, which is what the automated
rules split by income ratio.

Everything else is ignored. Malformed input never reaches the ledger: the bot
appends a canonically rendered block, re-parses the whole file, and rolls back
on any failure before confirming.

## Architecture

Functional core / imperative shell — `ledger.core` is the only public
business-logic API; contracts are frozen in [CONTRACT.md](CONTRACT.md).

| namespace       | role                                              | runtime  |
|-----------------|---------------------------------------------------|----------|
| `ledger.parse`  | journal text -> data (instaparse)                 | bb + JVM |
| `ledger.report` | balances, auto-posting rules, rendering           | bb + JVM |
| `ledger.core`   | public facade: expense, settlement, summary, ...  | bb + JVM |
| `ledger.bot`    | pure: updates -> effect descriptions              | bb + JVM |
| `ledger.store`  | append + validate + git commit per entry          | bb + JVM |
| `ledger.main`   | clj-tg-bot-api wiring (long-polling or webhook)   | JVM only |
| `ledger.cli`    | `bb ledger` subcommands                           | bb       |

Tests run on both runtimes: `bb test` and `clojure -M:test`.

## Deployment — Hetzner Cloud (IaC)

The reproducible path: OpenTofu config in [infra/](infra/) applied by the
`infra` GitHub Actions workflow (plan/apply/destroy) — one small ARM server,
household state in a private data repo, VM fully disposable. Setup and the
secrets inventory are in [infra/README.md](infra/README.md).

## Deployment — any VPS (manual)

1. **Bot**: create via [@BotFather](https://t.me/BotFather), keep the token.
   Make a dedicated group with the two of you + the bot. Get the group
   chat-id and both user-ids (e.g. via `getUpdates` in the browser after
   posting once: `https://api.telegram.org/bot<TOKEN>/getUpdates`).
2. **Data repo** on the VPS — the canonical ledger; the bot is its only writer:
   ```sh
   mkdir -p /srv/bbledger/data && cd /srv/bbledger/data
   git init
   git config user.name "bbledger-bot" && git config user.email "bot@localhost"
   cp /path/to/household.ledger .        # rules + history
   git add . && git commit -m init
   ```
3. **Config**: `cp config.sample.edn /srv/bbledger/data/config.edn` and fill in:
   ```clojure
   {:chat-id          -100123456789
    :ledger-file      "/data/household.ledger"
    :users            {111111111 "Alice", 222222222 "Bob"}
    :default-category ["Sonstiges"]
    :tz               "Europe/Berlin"}
   ```
4. **Token**: `echo 'BBLEDGER_BOT_TOKEN=...' > /etc/bbledger.env && chmod 600 /etc/bbledger.env`
5. **Image + units** — CI publishes the (public) image to GHCR on every
   push to main:
   ```sh
   cp deploy/bbledger-*.{service,timer} /etc/systemd/system/
   systemctl enable --now bbledger-bot.service bbledger-summary.timer
   ```
   Deploying a new version = `systemctl restart bbledger-bot` (the unit pulls
   `ghcr.io/schroedingberg/bbledger:latest` on start). Building locally
   instead: `docker build -f deploy/Dockerfile -t ghcr.io/schroedingberg/bbledger:latest .`

Local smoke run without Docker (JVM 21+):
`BBLEDGER_CONFIG=... BBLEDGER_BOT_TOKEN=... clojure -M:bot`

## Deployment — orkestr (PaaS, webhook)

The bot also runs as a web service on a container PaaS such as
[orkestr](https://orkestr.eu/docs): connect the repo, it builds the
[`deploy/Dockerfile`](deploy/Dockerfile) and gives the app a public HTTPS URL.
Setting **`BBLEDGER_WEBHOOK_URL`** flips `ledger.main` from long-polling to
**webhook** mode — an http-kit server that `setWebhook`s that URL with Telegram
and receives updates as POSTs (on `$PORT`, default `8080`; the exposed port and
Dockerfile are auto-detected). Unset, nothing changes: the Hetzner/systemd
deploy above keeps long-polling.

The container **self-provisions** the same way the Hetzner VM does, only
in-process: a babashka entrypoint ([`deploy/provision.clj`](deploy/provision.clj))
clones the private data repo into `/data` on first boot, and the app pushes each
new commit back — so a fresh volume comes up with your rules + history and stays
mirrored off-site (parity with cloud-init + the `bbledger-push` systemd unit).

1. `orkestr init` (link the repo) and add a **volume mounted at `/data`** — the
   ledger git repo lives there, same as `:ledger-file` in the config.
2. Set env vars:
   - `BBLEDGER_BOT_TOKEN` — bot token
   - `BBLEDGER_CONFIG=/data/config.edn`
   - `BBLEDGER_WEBHOOK_URL=https://<your-app-url>` — the URL orkestr assigns
     (its presence selects webhook mode)
   - `BBLEDGER_WEBHOOK_SECRET` *(optional)* — any string; else a random one is
     generated per boot. Telegram echoes it in the
     `X-Telegram-Bot-Api-Secret-Token` header, which the server verifies.
   - `BBLEDGER_DATA_REPO` — SSH URL of the private data repo (clone-on-boot)
   - `BBLEDGER_DEPLOY_KEY` — its deploy key (the same value as the infra
     `DATA_DEPLOY_KEY` secret); `BBLEDGER_GIT_HOST` if not `github.com`
   - `BBLEDGER_GIT_PUSH=1` — mirror each entry back to the data repo
3. `orkestr deploy .`, then send `12,30 Test` in the group and expect the ✓ —
   and the entry commit landing in the data repo moments later.

> On redeploy the entrypoint sees an existing `/data/.git` and **skips the
> clone** (the bot is the sole writer, so it never pulls). Changing rules or
> `config.edn` in the data repo therefore needs a manual pull or a volume
> re-seed. Seed the volume manually instead by leaving `BBLEDGER_DATA_REPO`
> unset and putting a git-initialised `household.ledger` + `config.edn` on it.

### Throwaway / ephemeral test

To try webhook mode against real Telegram without any persistence, run it
env-only: no volume, no data repo, no config file. With `BBLEDGER_DATA_REPO`
unset and a bare `/data`, the entrypoint **auto-seeds a throwaway ledger** from
the bundled `sample.ledger` (Alice/Bob), and config falls back to the baked
defaults (`resources/config.default.edn`) with the two test-specific fields
supplied as **plain env vars** — no EDN in the environment. Set:

- `BBLEDGER_BOT_TOKEN=<token>`
- `BBLEDGER_WEBHOOK_URL=<orkestr URL>`
- `BBLEDGER_CHAT_ID=-100…` — the test group
- `BBLEDGER_USERS=<your-id>:Alice` — `id:Name` pairs, comma-separated (the
  names Alice/Bob line up with `sample.ledger`'s split rules)

Everything (config, ledger, rules) comes up with zero setup and resets on
restart. Config layering in general: the baked defaults, then a
`BBLEDGER_CONFIG` file if present (the real deploy's `config.edn`), then those
env overrides on top.

**Always use a separate @BotFather bot for this, never the production token** —
Telegram allows one update consumer per token, so pointing a webhook at the prod
token would stop the prod bot's long-polling. (Merging this to `main` is itself
safe for the Hetzner bot: webhook mode is opt-in via `BBLEDGER_WEBHOOK_URL`,
which the VM doesn't set.)

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
