# bbledger

Tiny hledger-subset for splitting household costs by income ratio, plus a
Telegram bot for day-to-day recording. The ledger file is the database:
plain text, hledger-compatible, one git commit per bot entry.

## Reports

The ledger file is hledger-compatible (guarded by the conformance suite), so
ad-hoc reports come from hledger itself:

```sh
hledger -f household.ledger bal Verrechnung --auto   # settlement: negative = owes
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
| `ledger.report` | balance inference, auto-posting rules, sums       | bb + JVM |
| `ledger.core`   | public facade: expense, settlement, summary, ...  | bb + JVM |
| `ledger.bot`    | pure: updates -> effect descriptions              | bb + JVM |
| `ledger.store`  | append + validate + git commit per entry          | bb + JVM |
| `ledger.main`   | clj-tg-bot-api long-polling wiring                | JVM only |

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

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
