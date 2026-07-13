(ns ledger.store
  "Persistence: the ledger file is the database, one git commit per entry.
   The bot process is the only writer."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [ledger.core :as core])
  (:import (java.io File)))

(defn- git!
  "Run git in the ledger file's directory; throws on non-zero exit."
  [cfg & args]
  (let [dir (or (.getParent (File. ^String (:ledger-file cfg))) ".")]
    (apply p/shell {:dir dir :out :string :err :string} "git" args)))

(defn read-ledger
  "Slurp the ledger file named by (:ledger-file cfg)."
  [cfg]
  (slurp (:ledger-file cfg)))

(defn append!
  "Append txn as a canonical blank-line-separated block, re-parse the whole
   file to validate (restoring previous content and rethrowing on failure),
   then git add + commit \"expense: <desc>\" in the ledger's directory."
  [cfg txn]
  (let [file  (:ledger-file cfg)
        block (core/txn->str txn)
        prev  (read-ledger cfg)]
    (spit file (str prev (if (str/ends-with? prev "\n") "\n" "\n\n") block "\n"))
    (try
      (core/read-str (read-ledger cfg))
      (catch Exception e
        (spit file prev)
        (throw e)))
    (git! cfg "add" file)
    (git! cfg "commit" "-m" (str "expense: " (:description txn)))
    nil))

(defn undo!
  "Revert HEAD iff it is a bot expense commit; returns its <desc>, else nil."
  [cfg]
  (let [subject (str/trim (:out (git! cfg "log" "-1" "--format=%s")))]
    ;; git trims trailing whitespace in subjects: an empty description commits
    ;; as "expense:" (no space), so the separator space must be optional
    (when-let [[_ desc] (re-matches #"expense: ?(.*)" subject)]
      (git! cfg "revert" "--no-edit" "HEAD")
      desc)))
