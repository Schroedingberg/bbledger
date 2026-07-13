# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```sh
bb test              # full suite (lint-gated); needs `hledger` on PATH for the conformance oracle
bb lint              # clj-kondo over src + test (also runs as a dependency of `bb test`)
clojure -M:test      # same suite on the JVM — run both before committing; parity is a requirement
hledger -f sample.ledger bal Verrechnung --auto   # ad-hoc reports come from hledger (negative = owes)
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
and `ledger.report` (balance inference, auto-posting rules, account sums) are internals behind it.
There is no report rendering: the ledger file is hledger-compatible, so ad-hoc reports come from
hledger itself; the bot formats its own replies.

The bot pipeline: `ledger.main` (JVM wiring: clj-tg-bot-api long-polling, strictly sequential
single consumer) → `ledger.bot/handle-update` (pure: raw snake_case Telegram update map in,
**effect description** out: `{:record txn :reply s}` / `{:reply s}` / `{:undo? true}` / nil;
`:reply` may be a seq of ≤4096-char chunks, one `send!` each) → `ledger.bot/run-effects!`
validates the effect against the closed `Effect` schema, then executes it via injected fns
(`:append!` `:undo!` `:send!` `:delete!`), which is why the whole pipeline is testable under
bb with no network. Every branch is contained: injected-fn failures log to stderr and never
escape into the polling loop, and "⚠ not recorded" is sent only when `append!` itself failed.

Persistence (`ledger.store`): **the ledger file is the database.** `append!` renders the txn
canonically (`core/txn->str`), appends, re-parses the WHOLE file to validate (restoring the
previous content on failure), then makes one git commit per entry (`"expense: <desc>"`).
`undo!` reverts HEAD only if it is a bot expense commit. On the server, a systemd path unit
pushes the data repo after every change.

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

## Security posture (OWASP review 2026-07-10, re-scan 2026-07-11, fixes 2026-07-12)

Review docs: `.claude/security-rescan-2026-07-11.md` (supersedes the 2026-07-10 baseline) and
`.claude/test-suite-adequacy-2026-07-12.md`. All Medium/Low findings (M1–M4, L1–L6) and test-gap
items (P1–P7) are implemented:

- Containers blocked from the metadata endpoint (`deploy/bbledger-firewall.service`, M1).
- Workflow actions SHA-pinned, secrets step-scoped, PR runs of `deploy.yml` secret-free (M2).
- Images cosign-signed in CI (keyless, GitHub OIDC); autodeploy verifies the signature AND
  health-checks the new image (`clojure -M:bot check` = config + getMe) before restarting —
  a bad image leaves the old bot running (M3, P6).
- `/history` chunked below Telegram's 4096-char cap (M4); category segments schema-constrained
  against `;` smuggling (L1); GitHub SSH host keys pinned, no TOFU (L2).
- Container runs as non-root uid 1000 with `--cap-drop=ALL --security-opt=no-new-privileges`;
  cloud-init chowns `/srv/bbledger/data` to match — change one, change both (L3).
- All `run-effects!` branches contained; closed `Effect` schema makes effect-key typos loud;
  "⚠ not recorded" only when `append!` actually failed (L4, L6, P2).
- CI loads `ledger.main` and smoke-runs the built image (P1, P3); real getUpdates wire fixture
  in `test/ledger/fixtures/updates.json` (P7).

Accepted residuals (info-level, revisit when touching the area): SSH open to 0.0.0.0/0;
rejected senders not logged (would detect a leaked token being probed); `main.clj` slurps the
ledger before the allowlist check; the FIRST boot of a fresh VM pulls `:latest` unverified
(the cosign gate covers the autodeploy path only); the release announce curl exposes the token
in runner-local argv.
