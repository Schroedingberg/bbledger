#!/usr/bin/env bb
;; Container entrypoint (babashka, built-ins only — no bb.edn/classpath).
;; The PaaS equivalent of infra/cloud-init.yaml's clone block + the deploy key
;; write: when the data-repo env vars are set, install the git-over-ssh key and
;; clone the private data repo into /data if the volume is empty. Then replace
;; this process with the passed command (clojure -M:bot [summary]) via p/exec,
;; so SIGTERM from the runtime reaches the JVM directly. With none of the env
;; vars set (e.g. on Hetzner, where cloud-init already provisioned /data) this
;; is just the p/exec.
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
    (p/shell "git" "-C" "/data" "config" "user.email" "bot@localhost")))

(p/exec (vec *command-line-args*))
