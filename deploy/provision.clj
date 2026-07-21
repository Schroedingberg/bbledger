#!/usr/bin/env bb
;; Container entrypoint (babashka, built-ins only — no bb.edn/classpath).
;; The PaaS equivalent of infra/cloud-init.yaml's clone block + the deploy key
;; write: when the data-repo env vars are set, install the git-over-ssh key and
;; clone the private data repo into /data if the volume is empty. With no data
;; repo and a bare /data, seed a throwaway ledger instead (ephemeral demo/test
;; mode — pair with an inline BBLEDGER_CONFIG). Then replace this process with
;; the passed command (clojure -M:bot [summary]) via p/exec, so SIGTERM from the
;; runtime reaches the JVM directly. On Hetzner (cloud-init already provisioned
;; /data, which has .git) both branches are skipped and this is just the p/exec.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(let [key  (System/getenv "BBLEDGER_DEPLOY_KEY")
      repo (System/getenv "BBLEDGER_DATA_REPO")
      host (or (System/getenv "BBLEDGER_GIT_HOST") "github.com")]
  (when (seq key)
    (fs/create-dirs "/root/.ssh")
    (spit "/root/.ssh/id_ed25519" (str (str/trim key) "\n"))
    (fs/set-posix-file-permissions "/root/.ssh/id_ed25519" "rw-------")
    (spit "/root/.ssh/known_hosts"
          (:out (p/shell {:out :string} "ssh-keyscan" host)) :append true))
  ;; sole writer => never pull; clone only to seed a fresh volume
  (when (and (seq repo) (not (fs/exists? "/data/.git")))
    (p/shell "git" "clone" repo "/data")
    (p/shell "git" "-C" "/data" "config" "user.name"  "bbledger-bot")
    (p/shell "git" "-C" "/data" "config" "user.email" "bot@localhost"))
  ;; no data repo + bare /data => ephemeral throwaway ledger (demo/test)
  (when (and (not (seq repo)) (not (fs/exists? "/data/.git")))
    (fs/create-dirs "/data")
    (when-not (fs/exists? "/data/household.ledger")
      (fs/copy "/app/sample.ledger" "/data/household.ledger"))
    (doseq [a [["init" "-q"] ["config" "user.email" "bot@localhost"]
               ["config" "user.name" "bbledger-bot"] ["add" "."]
               ["commit" "-qm" "seed (ephemeral)"]]]
      (apply p/shell "git" "-C" "/data" a))))

(p/exec (vec *command-line-args*))
