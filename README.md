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

1. `orkestr init` (link the repo) and add a **volume mounted at `/data`** — the
   ledger git repo lives there, same as `:ledger-file` in the config.
2. Seed the volume once with your `household.ledger` + `config.edn` (as in the
   manual steps above), initialised as a git repo.
3. Set env vars: `BBLEDGER_BOT_TOKEN`, `BBLEDGER_CONFIG=/data/config.edn`,
   `BBLEDGER_WEBHOOK_URL=https://<your-app-url>` (the URL orkestr assigns), and
   optionally `BBLEDGER_WEBHOOK_SECRET` (any string — else a random one is
   generated per boot; Telegram echoes it in the
   `X-Telegram-Bot-Api-Secret-Token` header, which the server verifies).
4. `orkestr deploy .`, then send `12,30 Test` in the group and expect the ✓.

> **Persistence caveat:** entries are committed to the `/data` volume (which
> survives redeploys) but **not** pushed off-site — the systemd path unit that
> mirrors to the private data repo only exists on the Hetzner VM. Off-site
> push-back from the container is a follow-up. Back up the volume meanwhile.

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
