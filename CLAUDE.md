# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```sh
bb test              # full suite (lint-gated); needs `hledger` on PATH for the conformance oracle
bb lint              # clj-kondo over src + test (also runs as a dependency of `bb test`)
clojure -M:test      # same suite on the JVM — run both before committing; parity is a requirement
bb ledger bal Verrechnung --auto   # the CLI against sample.ledger (negative = owes)
```

Run a single test namespace under babashka (no task for it):

```sh
bb -e "(babashka.classpath/add-classpath \"test\") (require 'ledger.core-test) (clojure.test/run-tests 'ledger.core-test)"
```

Run the bot locally (JVM 21+ only): `BBLEDGER_CONFIG=... BBLEDGER_BOT_TOKEN=... clojure -M:bot`
(`clojure -M:bot summary` sends a one-shot summary and exits).

## Hard constraints

- **Minimum code, afternoon-comprehensible.** This is a 2-person household tool maintained solo.
  Challenge every added abstraction; prefer maintained libraries over hand-rolled infrastructure.
- **Dual runtime.** All source except `ledger.main` must run under BOTH babashka and JVM Clojure.
  The trick: `instaparse-bb` (bb.edn) and `instaparse` (deps.edn) expose the same
  `instaparse.core` namespace, so `ledger.parse` is runtime-agnostic. `babashka.process` is
  built into bb and a lib on the JVM. `ledger.main` requires clj-tg-bot-api (JVM-only) and is
  never loaded by tests or bb.
- **CONTRACT.md is the merge interface.** Data shapes (txn/posting/rule), the core API signatures,
  bot effect shapes, and the config shape are frozen there. Executable versions: the malli
  schemas `ledger.core/Expense` and `ledger.bot/Config`.
- **This repo is public.** Household state (`household.ledger`, `config.edn`) lives in a separate
  private data repo, never here. `sample.ledger` and all test fixtures are synthetic
  (Alice/Bob, 0.6/0.4 ratio) — keep it that way. `config.edn` is gitignored.

## Architecture

Functional core / imperative shell. **`ledger.core` is the single public business-logic API**
(pure, data in/data out); `ledger.parse` (text→data, instaparse grammar in `resources/ledger.bnf`)
and `ledger.report` (balance inference, auto-posting rules, rendering) are internals behind it.

The bot pipeline: `ledger.main` (JVM wiring: clj-tg-bot-api long-polling by default, or —
when `BBLEDGER_WEBHOOK_URL` is set, e.g. on a PaaS like orkestr — an http-kit webhook server
that `setWebhook`s the URL and serializes POSTs through the same pipeline under a `locking`,
preserving the single-writer discipline) → `ledger.bot/handle-update` (pure: raw snake_case Telegram update map in,
**effect description** out: `{:record txn :reply s}` / `{:reply s}` / `{:undo? true}` / nil) →
`ledger.bot/run-effects!` executes effects via injected fns (`:append!` `:undo!` `:send!`),
which is why the whole pipeline is testable under bb with no network.

Persistence (`ledger.store`): **the ledger file is the database.** `append!` renders the txn
canonically (`core/txn->str`), appends, re-parses the WHOLE file to validate (restoring the
previous content on failure), then makes one git commit per entry (`"expense: <desc>"`).
`undo!` reverts HEAD only if it is a bot expense commit. Off-site mirroring has two
interchangeable paths: on the Hetzner VM a systemd path unit pushes the data repo after every
change; on a PaaS (no systemd) `store/push!` does it in-process, gated on env `BBLEDGER_GIT_PUSH`
and driven by `ledger.main` after each record/undo. Seeding a fresh volume is likewise split:
cloud-init clones on the VM, `deploy/provision.clj` (the container's babashka entrypoint) clones
when `BBLEDGER_DATA_REPO`/`BBLEDGER_DEPLOY_KEY` are set.

## Non-obvious invariants

- Amounts are BigDecimal everywhere. **Never compare with `=`** (scale-sensitive: `18.665M ≠ 18.6650M`);
  use `==`, `compareTo`, or `.stripTrailingZeros` normalization (see `essence` in store_test).
- Descriptions cannot contain `;` (starts a ledger comment → silent truncation on re-parse) or
  newlines. `core/expense` rejects them; the bot sanitizes before building the txn.
- Git trims trailing whitespace in commit subjects: an empty description commits as `"expense:"`
  (no space) — `store/undo!` matches the separator space optionally for this reason.
- The payer lives on the Assets side (`Assets:<Person>:Cash`), never in the Expenses hierarchy —
  the automated split rules (`= Expenses` …) depend on this.
- Expense trigger is strict: leading amount WITH decimals (`45.60` / `12,30`). "2 Minuten" must
  not record. Commands may carry `@botname` suffixes.
- `sample.ledger` must stay hledger-parseable (postings indented). If it changes, the hardcoded
  totals in `conformance_test` must be recomputed from `hledger -f sample.ledger bal --flat
  --no-total -O csv [--auto]`.
- Tests include test.check properties and store roundtrips against real temp git repos.
  Cross-namespace fixture reuse is deliberate (`ledger.bot-test/rules`, `ledger.properties-test`
  generators).

## CI / deployment

CI (`.github/workflows/ci.yml`): clj-kondo + `bb test` + `clojure -M:test` (hledger installed as
oracle), then a **multi-arch** (amd64+arm64) image to `ghcr.io/schroedingberg/bbledger:latest`
on green main. Deploying a new app version to the server = push to main, then
`ssh root@<server> systemctl restart bbledger-bot` (the unit pulls `:latest` on start).

Infra (`infra/`, OpenTofu, `infra` workflow with plan/apply/destroy + location/server_type
inputs): one Hetzner server in a **dedicated project**, default **stateless mode** — no tfstate
backend; apply first deletes the bbledger resources by name via hcloud CLI and recreates
(setting the `STATE_*` secrets switches to persisted S3-compatible state). cloud-init injects
the `deploy/` systemd units **verbatim** (`file()` in main.tf) — editing `deploy/*` changes both
the manual-VPS path and provisioning. The VM is disposable: cloud-init clones the private data
repo; every entry is pushed back to it. Server SSH keys come from the Hetzner project
(`hcloud_ssh_keys` data source), not from variables — Hetzner rejects duplicate key material.

Secrets inventory and provisioning runbook: `infra/README.md`.
