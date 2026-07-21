# bbledger — shared data contract (FROZEN)

Three namespaces, three owners. **Do not change these signatures or the data
shapes** — they are the merge interface. Touch only your own file.

- `src/ledger/parse.clj`   + `resources/ledger.bnf`  — Parser (Agent 1)
- `src/ledger/report.clj`                            — Report engine (Agent 2)
- `src/ledger/cli.clj`                               — CLI (Agent 3)

The program must compile and run end-to-end at all times (stubs are provided for
the files you don't own). Test with `sample.ledger` in this directory.

## Core data shapes

```clojure
;; result of parsing
{:rules        [<rule> ...]
 :transactions [<txn>  ...]}

;; <txn>
{:date        "2026-06-26"          ; ISO string
 :description "Kueche"              ; string, may be "" when absent
 :postings    [<posting> ...]}

;; <posting>
{:account   ["Assets" "Bob" "Cash"]  ; vector of segments (split on ":")
 :amount    -8927M                       ; bigdec, or nil when ELIDED
 :commodity "€"                          ; string, or nil when elided
 :virtual?  false}                       ; true if account written in (parens)

;; <rule>  (an automated-posting rule, source line begins with "=")
{:query    [<term> ...]              ; see below
 :postings [<rule-posting> ...]}

;; <term>   (one token of the query)
{:type :account  :pattern "Expenses"}          ; account-name match (substring)
{:type :not-desc :pattern "Kaution"}           ; transaction description must NOT contain
{:type :desc     :pattern "..."}               ; transaction description must contain

;; <rule-posting>
{:account    ["Verrechnung" "Alice"]
 :multiplier -0.6M                   ; bigdec, from "*-0.6"
 :virtual?   true}
```

Amounts are **bigdec** (`120M`, `-8927M`, `70.53M`). Accounts are **vectors of
strings**. Dates are **strings**. Single commodity `"€"` throughout.

## Namespace `ledger.parse` (Agent 1)

```clojure
(parse-string [s])   ; => {:rules [...] :transactions [...]}
(parse-file   [path]); => same   (slurp + parse-string)
```

Grammar lives in `resources/ledger.bnf`, loaded via instaparse-bb. Must handle,
from `sample.ledger`:

- Full-line comments: lines starting with `;`. Inline comments: `... ; text` at
  end of a posting line — strip them.
- Blank lines separate transactions.
- Commodity may be **prefix or suffix**: `€120`, `€-8927`, `1078€`, `70.53€`.
- Sign anywhere sensible: `€-6000`, `-6000€`.
- **Elided amount**: a posting line with an account and no amount → `:amount nil
  :commodity nil`. (Inference happens in the report layer, NOT here.)
- Inconsistent indentation (2 or 3 spaces) — any leading whitespace = posting.
- Transaction header: `DATE DESCRIPTION` where DESCRIPTION may be empty.
- Rule block: line begins `=` then a query; following indented lines are
  rule-postings of the form `(Account:Sub)    *-0.6   ; comment`.
- Account names are colon-separated; split into the segment vector.

instaparse-bb usage:
```clojure
(require '[instaparse.core :as insta])
(def parser (insta/parser (slurp "resources/ledger.bnf")))
(insta/parse parser text)        ; or (parser text)
(insta/transform {:txn ...} tree); transform is available
```

## Namespace `ledger.report` (Agent 2)

Pure functions over the data shapes above. Develop with the inline fixture in
your stub (do not depend on the parser running).

```clojure
(infer-balances [txns])          ; fill each txn's single nil :amount so the
                                 ; NON-virtual postings sum to 0 (commodity "€").
                                 ; set its :commodity to "€" too.

(apply-auto [rules txns])        ; for each txn, for each rule, for each
                                 ; NON-virtual posting p in the txn that the
                                 ; rule's query matches, append the rule's
                                 ; postings with :amount = multiplier * (p :amount),
                                 ; :virtual? true. Returns updated txns.
                                 ; (query :account term matches p's account;
                                 ;  :desc/:not-desc match the txn description.)

(render [txns opts])             ; => string. hledger-style balance.
                                 ; opts: {:tree? true :commodity "€"
                                 ;        :accounts ["Verrechnung"]}  ; optional
                                 ;        :accounts filters to matching top/substr.
(balance-sheet [txns opts])      ; => string. Assets/Liabilities/Equity sections.
(income-statement [txns opts])   ; => string. Revenues(Income)/Expenses sections.
```

Account type by first segment: `Assets`, `Liabilities`, `Equity`,
`Income`/`Revenues`, `Expenses`. Everything else (e.g. `Verrechnung`) is "other"
— shown by `render` but excluded from bs/is.

`render` sums ALL postings (real + virtual). Virtual postings are unbalanced by
design (that's how `Verrechnung` works) — do not try to force them to zero.

## Namespace `ledger.cli` (Agent 3)

Follow https://github.com/babashka/cli docs (dispatch table). Provide `-main`.

Subcommands → behaviour:
- `bal` / `balance`   → `report/render`
- `bs`  / `balancesheet` → `report/balance-sheet`
- `is`  / `incomestatement` → `report/income-statement`

Options: `--file` / `-f` (path; default `"sample.ledger"`), `--auto` (boolean),
`--tree` (boolean, default true). A trailing positional arg to `bal` is an
account filter passed as `:accounts`.

Pipeline to wire:
```clojure
(let [{:keys [rules transactions]} (parse/parse-file file)
      txns (cond->> (report/infer-balances transactions)
             auto (report/apply-auto rules))]
  (print (report/render txns {:tree? tree :commodity "€" :accounts accounts})))
```

Develop against the stub parse/report (they return contract-shaped data), so
`bb ledger bal -f sample.ledger` runs even before the others land.

## Total budget: ~500 LOC across all files.

---

# Phase 2 — Telegram bot (FROZEN)

Four new namespaces, three owners. Same rules as above: the signatures and
shapes below are the merge interface — touch only your own files. The test
suite (`test/ledger/{core,bot,integration}_test.clj`) is the acceptance
criterion and may not be edited.

- `src/ledger/core.clj`  — public business-logic facade (Agent A)
- `src/ledger/bot.clj`   — pure decisions + formatting   (Agent B)
- `src/ledger/store.clj` + `src/ledger/main.clj` + `deps.edn` (Agent C)

Budget: ~200 LOC total. Runtimes: everything except `main.clj` must run under
**babashka** (`bb test`) *and* JVM Clojure; `main.clj` is JVM-only (it
requires `clj-tg-bot-api`) and is never loaded by tests.

## Config shape

```clojure
{:chat-id          -100123                   ; the dedicated group
 :ledger-file      "/data/household.ledger"  ; canonical copy, own git repo
 :users            {111111 "Alice", 222222 "Bob"} ; telegram-id -> person
 :default-category ["Sonstiges"]
 :tz               "Europe/Berlin"}
```
Bot token comes from env `BBLEDGER_BOT_TOKEN`, never from the config file.
Executable contract: the malli schema `ledger.bot/Config`, validated at
startup by `ledger.main/load-config`. Likewise `ledger.core/Expense` is the
executable input contract of `core/expense`.

## Telegram update shape (raw wire format, snake_case keywords)

```clojure
{:update_id 1
 :message {:message_id 10
           :date 1783602000                  ; unix seconds
           :from {:id 111111}
           :chat {:id -100123}
           :text "45.60 Router #Umzug:Kueche"}}
```
`bot/handle-update` consumes THIS shape. If clj-tg-bot-api normalizes keys,
`main.clj` adapts back to it — the bot namespace stays wire-shaped.

## Namespace `ledger.core` (Agent A) — pure, data in / data out

The ONE public API of the business logic. `parse`/`report` become internals
behind it (you may promote a private `report` helper to public if needed, but
existing behavior is guarded by the conformance suite).

```clojure
(read-str [s])        ; ledger text -> {:rules [...] :transactions [...]}
                      ; parsed AND balance-inferred (shapes as in Phase 1)
(expense [m])         ; {:date "2026-07-09" :payer "Alice"
                      ;  :category ["Umzug" "Kueche"] :amount 45.60M
                      ;  :description "Router"}
                      ; -> txn with exactly two real postings:
                      ;    Assets:<payer>:Cash  -amount, Expenses:<cat...> +amount
                      ; throws ex-info when amount not pos bigdec, payer blank,
                      ; category empty, or date not YYYY-MM-DD
(txn->str [txn])      ; -> canonical ledger block, e.g.
                      ;    "2026-07-09 Router\n  Assets:Alice:Cash  €-45.60\n  Expenses:Umzug:Kueche  €45.60"
                      ; MUST round-trip through read-str
(balances [ledger opts]) ; -> {account-vec bigdec}; opts {:auto? bool
                      ;    :accounts ["substr" ...]} (filter like report/render)
(settlement [ledger]) ; -> {"Alice" 39.11M, "Bob" -39.11M}
                      ; rules applied, Verrechnung:<Person>[:*] summed per person
                      ; sign convention as in sample.ledger: negative = owes
(summary [ledger opts]) ; opts {:from "2026-07-01" :to "2026-07-31"} (inclusive,
                      ; nil = unbounded; ISO strings compare lexicographically)
                      ; -> {:settlement <all-time settlement>
                      ;     :by-category {["Umzug" "Kueche"] 45.60M ...} ; Expenses
                      ;                  ; accounts in range, "Expenses" prefix dropped
                      ;     :total 45.60M}                    ; sum of by-category
```

Amounts are bigdec — compare with `==` in tests, never `=` (scale differs).

## Namespace `ledger.bot` (Agent B) — pure

```clojure
(parse-msg [s])   ; strict trigger: text starts with an amount that has decimals
                  ; (dot or comma) OR a € on either side (€ makes integers ok):
                  ; "45.60 Router" | "12,30 Drogerie" | "50€ Pizza" | "€ 50,50 X"
                  ;   -> {:amount 45.60M :description "Router" :category nil}
                  ; "#Cat:Sub" token anywhere -> :category ["Cat" "Sub"], removed
                  ;   from description; € never leaks into the description
                  ; no trigger ("2 Minuten bin ich da", "50 Pizza", "hallo",
                  ;   "/bal") -> nil

(handle-update [config ledger-text update])  ; -> effect description:
  ;; expense msg from known user in the right chat:
  ;;   {:record <txn from core/expense> :reply "✓ …" :delete-msg <message_id>}
  ;;     txn :date = message :date unix ts converted in config :tz
  ;;     payer = (users (:id from)); category defaults to :default-category
  ;;     :delete-msg = the sender's message; deleting it (after the record
  ;;     succeeded) communicates that the ✓ reply, not the message, is the record
  ;; "/bal"      -> {:reply <settlement formatted, contains names + amount>}
  ;; "/summary"  -> {:reply <month-to-date summary from message date>}
  ;; "/undo"     -> {:undo? true}
  ;; "/history"  -> {:reply <the raw ledger text>}
  ;; "/help"     -> {:reply <usage text>}
  ;; expense-intent text that doesn't trigger (decimal amount anywhere, or
  ;;   text opening with a number — incl. "50 Pizza", "2 Minuten bin ich da")
  ;;             -> {:reply "⚠ …"} nudge instead of silence
  ;; :edited_message updates NEVER record (double-booking risk); expense-looking
  ;;   edit -> {:reply "⚠ …"} nudge, anything else -> nil
  ;; wrong chat-id, unknown sender, or non-expense-looking chatter -> nil

(run-effects! [effect {:keys [append! undo! send! delete!]}])
  ;; the ONLY impure-ish fn here; side effects injected:
  ;;   append! : txn -> nil (throws on invalid)   undo! : () -> desc | nil
  ;;   send!   : text -> nil                      delete! : message-id -> nil
  ;; {:record t :reply r :delete-msg id}
  ;;   -> (append! t), (send! r), then (delete! id); on append/send throw send
  ;;      "⚠ …" and skip the delete; on delete throw warn via send! (the record
  ;;      stands — delete needs the bot to be a group admin with delete rights)
  ;; {:reply r}           -> (send! r)
  ;; {:undo? true}        -> (undo!) -> desc: send "↩ … <desc>", nil: "nothing to undo"
  ;; nil                  -> no-op
```

## Namespaces `ledger.store`, `ledger.main` + `deps.edn` (Agent C)

```clojure
;; store — impure; git via babashka.process (built into bb, lib on JVM)
(read-ledger [cfg])   ; slurp (:ledger-file cfg)
(append! [cfg txn])   ; core/txn->str, append as separate block, re-parse the
                      ; WHOLE file (core/read-str) to validate; on failure
                      ; restore previous content and throw; on success
                      ; git add + commit -m "expense: <desc>" in the file's dir
(undo! [cfg])         ; HEAD commit message starts with "expense: "?
                      ;   yes: git revert --no-edit HEAD -> return the <desc>
                      ;   no:  return nil (never revert non-bot commits)
(push! [cfg])         ; git push origin HEAD, tolerant (swallow failure, retried
                      ; next entry); ledger.main calls it after record/undo when
                      ; env BBLEDGER_GIT_PUSH is set (PaaS off-site mirror)

;; main — JVM-only entry points
(-main [& args])      ; no args: load config (path from env BBLEDGER_CONFIG,
                      ; default "config.edn"), start clj-tg-bot-api
                      ; long-polling — OR, when env BBLEDGER_WEBHOOK_URL is set,
                      ; an http-kit webhook server on $PORT (default 8080) that
                      ; setWebhooks that URL and processes POSTs identically;
                      ; per update: handle-update -> run-effects!
                      ; with store fns + library send-message. Updates are
                      ; processed SEQUENTIALLY (single writer).
                      ; args ["summary"]: one-shot month-to-date summary to
                      ; the group, then exit (systemd timer entry point).
```

`deps.edn`: instaparse (JVM twin of instaparse-bb — same `instaparse.core`
ns, source unchanged), com.github.marksto/clj-tg-bot-api, babashka/process,
an http client supported by clj-tg-bot-api, and a `:test` alias running the
same suite on the JVM.
