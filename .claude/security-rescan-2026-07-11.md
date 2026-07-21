# DevSecOps Re-Scan — bbledger (2026-07-11)

Baseline: CLAUDE.md "Security TODO (OWASP review, 2026-07-10)". Scope re-verified against
HEAD `a6ea7cc`. Methodology: `.claude/skills/owasp-security/SKILL.md` (OWASP Top 10:2025).

## Verdict on the baseline

All seven baseline findings are **still open**. None were fixed. Three are **degraded**
(M2, M3, L4) by the 2026-07-11 changes; one **new Medium** (M4) and two new Lows were
introduced. PR-gated main with `enforce_admins` and required checks is a genuine
improvement (A08) — the degradations are side effects of how it was wired, not of the idea.

## Findings

### M1 — Secrets in cloud-init user_data readable via metadata endpoint — STILL OPEN, unchanged
`infra/cloud-init.yaml` (rendered by `templatefile()` in `infra/main.tf:77-90`) embeds
`bot_token`, `data_deploy_key`, `ghcr_token` in user_data; Hetzner serves user_data at
`169.254.169.254`, reachable from inside the bot container (no `DOCKER-USER` rule in runcmd).
**Fix:** `iptables -I DOCKER-USER -d 169.254.169.254 -j DROP` as the first runcmd entry.

### M2 — Mutable-tag actions + job-level secret env — STILL OPEN, DEGRADED
All actions in both workflows pinned by mutable tag. Degradation: (1) `deploy.yml:19` now
triggers on EVERY pull_request, and its job-level env (`deploy.yml:27-40`) hands
HCLOUD_TOKEN (r/w), BBLEDGER_BOT_TOKEN, DATA_DEPLOY_KEY, GHCR_PULL_TOKEN and STATE keys to
every step incl. tag-pinned third-party actions, on every PR run. (2) `ci.yml`'s new jobs
run tag-pinned actions with elevated GITHUB_TOKEN (`image`: packages: write; `release`:
contents: write) — packages:write is exactly what M3/CD turns into production RCE.
Mitigating: fork PRs never receive secrets; only owner has write. Exposure vector =
compromised action tags, not PR authors.
**Fix:** pin `uses:` by commit SHA; move deploy.yml env from job level to the steps that need it.

### M3 — Unverified `:latest` image — STILL OPEN, DEGRADED (CD removed the human)
`bbledger-autodeploy.*` pulls `:latest` every 5 min and restarts on digest change. Green CI →
GHCR push → VM restart is now fully unattended: anything with GHCR write (GITHUB_TOKEN with
packages:write in ci.yml, any PAT with write:packages, or a compromised action in the image
job) has root-container code exec on the VM within 5 minutes.
**Fix:** cosign keyless sign in CI (one step after build-push) + `cosign verify` (identity =
repo workflow, issuer = GitHub OIDC) in the autodeploy ExecStart before restart. Pull-based
CD itself is right — keep it.

### M4 — NEW: `/history` replies with the entire raw ledger; guaranteed send failure past 4096 chars
`src/ledger/bot.clj:85` — `"/history" {:reply ledger-text}`. (a) Availability: Telegram
sendMessage caps at 4096 chars; once household.ledger exceeds that, `send!` throws 400 and
(via L4) the exception escapes into the polling loop — deterministic, user-triggerable,
forever. (b) Data exposure: whole financial history as one plaintext message — acceptable
by design (allowlist), but persists for anyone later added to the chat.
**Fix:** chunk in `main.clj`'s `:send!` (≤4096, split on newlines) or use sendDocument for
/history; do together with L4.

### L1 — `#Category` bypasses `;` sanitization — STILL OPEN, unchanged
`Expense` schema constrains only `:description`; `bot.clj:50` extracts categories with
`#(\S+)`; sanitizer covers description only. `12,30 x #Evil;Cat` commits, re-parses truncated.
**Fix:** constrain category segments in the schema (e.g. `[:re #"^[^;\r\n:()\s]+$"]`) + bot
test for `#A;B`.

### L2 — TOFU `ssh-keyscan github.com` — STILL OPEN
`infra/cloud-init.yaml:44`. **Fix:** pin GitHub's published SSH host keys via `write_files`.

### L3 — Container root, no hardening — STILL OPEN, surface slightly larger
No `USER` in Dockerfile; no `--cap-drop=ALL --security-opt=no-new-privileges` on any docker
run site (bot, summary, autodeploy-restarted). Root-in-container is what escalates M1/M3
into "read the deploy key, own the data repo."
**Fix:** USER in Dockerfile + hardening flags on the docker run lines (units injected
verbatim — covers both deploy paths).

### L4 — Unprotected `run-effects!` branches — STILL OPEN, DEGRADED
`src/ledger/bot.clj:111-131`: only `record` is wrapped; `undo?` and `reply` let git/Telegram
errors escape into the polling loop. Now degraded: the reply branch is the hot path
(/history M4, nudges, echoes). Also: the record branch's outer catch calls `send!` — if
Telegram is down, that throws too and escapes.
**Fix:** wrap `undo?`/`reply` branches; outermost failure handler should log, not send!.

### L5 — NEW: bot token inline-templated into the release curl
`ci.yml` announce step: token `${{ }}`-interpolated into the script, visible in curl argv on
the runner. Logs are masked; release job runs only on main; fork PRs get no secrets.
**Fix:** pass token/chat-id via step `env:` and expand in-shell (`${TOKEN}`).

### L6 — NEW: false "⚠ not recorded" after a successful append → duplicate-entry risk
`bot.clj:118-126`: if `append!` succeeds but the ✓ `send!` throws, the catch replies
"⚠ not recorded" — false; invites a duplicate re-send; original message not deleted.
**Fix:** catch append! and the ✓ send separately; never claim "not recorded" once append!
returned.

### Posture notes (info)
- Branch protection + auto-merge: net positive. Fork PRs can never satisfy the required
  `tofu` check (no secrets) — silent contributor-blocker, acceptable for a solo repo.
- `infra/.terraform.lock.hcl` committed → provider hashes verified. Good.
- Version-job race on concurrent merges: second `gh release create` fails. Cosmetic.
- Still valid info items: SSH open to 0.0.0.0/0; rejected senders not logged (leaked-token
  probe detector); main.clj slurps the ledger before the allowlist check.

## Priority order

1. **M4 + L4 together** (small changes in bot.clj/main.clj; M4 is a guaranteed future outage)
2. **M3** (cosign verify in autodeploy — highest-leverage supply-chain control under CD)
3. **M2** (SHA-pin + step-scope env; also shrinks M3's surface)
4. **M1**, then L6, L1, L3, L2, L5.

## Proposed replacement CLAUDE.md "Security TODO" section

(see agent transcript / conversation of 2026-07-11 — includes all items above with the
degraded/new markers, ready to paste)
