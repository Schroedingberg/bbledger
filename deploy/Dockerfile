# Build from the repo root:  docker build -f deploy/Dockerfile -t bbledger .
# JVM 21+ required (clj-tg-bot-api uses virtual threads).
FROM clojure:temurin-21-tools-deps-alpine

# git for the commit-per-entry store; openssh-client for git-over-ssh + the
# entrypoint's ssh-keyscan; curl to fetch babashka. safe.directory because /data
# is a host-owned volume with a different uid than the container user.
RUN apk add --no-cache git openssh-client curl \
    && git config --global --add safe.directory '*'

# babashka runs the container entrypoint (deploy/provision.clj). The published
# babashka:-alpine image is amd64-only, so fetch the arch-appropriate *static*
# (musl) release directly — the prod server is arm64. TARGETARCH is supplied by
# buildx; a builder without it fails the case loudly.
ARG BB_VERSION=1.12.218
ARG TARGETARCH
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) bbarch=amd64 ;; \
      arm64) bbarch=aarch64 ;; \
      *) echo "unsupported TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/bb.tar.gz \
      "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${bbarch}-static.tar.gz"; \
    tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb; \
    rm /tmp/bb.tar.gz; \
    bb --version

WORKDIR /app
COPY deps.edn .
RUN clojure -P -M:bot
COPY src src
COPY resources resources
COPY deploy/provision.clj /app/provision.clj
# baked ledger for the entrypoint's ephemeral (no-data-repo) seed
COPY sample.ledger /app/sample.ledger

# set by CI to the release tag; "which version is running?" =
# docker exec bbledger-bot printenv BBLEDGER_VERSION (bb restart prints it)
ARG VERSION=dev
ENV BBLEDGER_VERSION=$VERSION

ENV BBLEDGER_CONFIG=/data/config.edn

# webhook mode (BBLEDGER_WEBHOOK_URL set) serves here; long-polling ignores it.
# orkestr auto-detects the exposed port; it also injects $PORT, which the app
# prefers over this default.
EXPOSE 8080

# provision.clj clones the data repo (when BBLEDGER_DATA_REPO/_DEPLOY_KEY are
# set) then p/exec's the CMD, so `docker run img` → clojure -M:bot, and the
# summary unit's `img clojure -M:bot summary` override flows straight through.
ENTRYPOINT ["bb", "/app/provision.clj"]
CMD ["clojure", "-M:bot"]
