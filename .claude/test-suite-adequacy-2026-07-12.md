# bbledger test-suite adequacy: "green CI, dead bot?" — gap analysis (2026-07-12)

**Verdict: several whole defect classes pass today's CI and only manifest on the production
server — including the class that crash-loops the container within 5 minutes of a merge,
unattended.** Below the `ledger.main` boundary the suite is strong (properties, hledger
oracle, real temp git repos, dual-runtime parity). Above it — JVM wiring, Docker artifact,
Telegram contract, real config — zero automated coverage.

## Verified facts

1. **Nothing ever loads `ledger.main`.** No test requires it; `clojure -P -M:bot` in the
   Dockerfile only downloads deps; clj-kondo passes a misspelled external require with
   exit 0 (verified empirically). A typo'd require in main.clj sails through CI, builds
   `:latest`, gets released + announced, and autodeploys into a Restart=always crash loop
   with no rollback (autodeploy compares image IDs — never self-heals until a fixed release).
2. **Effect keys structurally unvalidated**: `run-effects!` destructures only
   `{:record :reply :undo? :delete-msg}`; unknown keys silently ignored — the `:replly`
   incident is a permanent class. Injected-fns maps duplicated in main.clj and
   integration_test.clj with nothing keeping them in sync.
3. **The polling loop survives escaped exceptions** (verified in clj-tg-bot-api 1.2.273
   source: consumer catches Throwable, logs, continues). So L4 = silent no-ops, not death.
4. **`make-request!` throws on Telegram failure by default** → `/history` past 4096 chars
   is a deterministic silent no-op for the user (exception logged, nothing sent).
5. **The record branch lies**: `append!` succeeds + ✓ `send!` throws → "⚠ not recorded" —
   false; invites duplicate re-send. Tests only simulate append! failing.

## (a) Defect classes vs coverage

| # | Class | Caught today? |
|---|---|---|
| 1 | Syntax/require/lib-drift in ledger.main | NOT — no CI step loads it |
| 2 | Broken Docker artifact (resources missing, base drift) | NOT — image never run in CI |
| 3 | Effect-map key typo in a new command (`:replly` class) | NOT for new keys |
| 3b | run-effects! gains injected fn main.clj forgets | NOT — duplicated maps |
| 4 | Config schema tightened vs stale real config.edn | NOT (impossible in public CI) |
| 5 | Real-world input drift (NBSP class; captions, threads) | PARTIAL — hand-built fixtures only |
| 6 | /history > 4096 chars | NOT — deterministic future incident |
| 7 | "not recorded" lie after successful commit | NOT |
| 8 | L4 undo?/reply branch exceptions | NOT (verified: silent drop, not crash) |
| 9 | Store append/undo/validation-restore | WELL CAUGHT |
| 10 | Dual-runtime divergence | WELL CAUGHT |
| 11 | Bad/expired token at startup | NOT (secret; post-deploy only) |
| 12 | Broken deploy unnoticed (no health gate/rollback) | NOT |

## (b) Prioritized recommendations

- **P1 (5 min)** — CI step after `clojure -M:test`:
  `clojure -M -e "(require 'ledger.main)"` — closes class 1, the headline.
- **P2 (<1 h)** — malli `Effect` closed-map schema next to `Config` in bot.clj, validated
  at the top of `run-effects!` (throw on invalid non-nil). Turns the next `:replly` into a
  loud failure. + 1-line assert the fns map has all four keys (3b). Malli already a dep.
- **P3 (1-2 h)** — image smoke in CI: build native-arch `load: true, tags: bbledger:smoke`
  before the multi-arch push (buildx cache makes push ~free), then
  `docker run --rm bbledger:smoke clojure -M -e "(require 'ledger.main) (println :ok)"`.
  Verifies deps baked, resources/ledger.bnf present. Skip the fake-token variant.
- **P4 (1-2 h each)** — app fixes surfaced by the analysis: (i) chunk /history replies at
  line boundaries <~3500 chars (allow :reply string-or-seq — smallest contract change);
  (ii) wrap ONLY append! in the "not recorded" catch; send-failure after commit must
  log/warn, never claim non-recording. Tests for both.
- **P5 (15 min)** — wrap undo?/reply branches (L4), downgraded: feedback quality, not survival.
- **P6 (2-3 h, the one heavy item worth it)** — health-gated autodeploy: add a `check` arg
  to -main (~6 lines: load-config, ->client, getMe, exit 0/1; getMe doesn't conflict with
  polling), and in bbledger-autodeploy.service run the pulled image with
  `clojure -M:bot check` against the real /data BEFORE restarting; failure leaves the old
  bot running, timer retries. Converts "unattended broken deploy" into "unattended refused
  deploy". Covers classes 4, 11, and shields 1-2 at runtime.
- **P7 (30 min)** — one sanitized real getUpdates JSON fixture (message, edit,
  photo-with-caption) driven through handle-update. Insurance for class 5. One file only.

## (c) What NOT to build

No mock-Telegram e2e harness (would mostly test the lib; exceeds the whole suite's
comprehensibility budget). No staging bot/second VM. No AOT/uberjar step (P1 does it).
No coverage thresholds/mutation testing/wire fuzzing. No tests for ledger.cli (dev-only).
No config-migration machinery (P6 is the honest answer). No arm64 qemu smoke.

**Suggested order:** P1+P2 in one sitting (both past-incident classes + the crash-loop
class), P3 next merge, P4 soon (deterministic outage + money-correctness lie), P6 when
next touching infra, P5/P7 opportunistically.
