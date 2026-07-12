# Build from the repo root:  docker build -f deploy/Dockerfile -t bbledger .
# JVM 21+ required (clj-tg-bot-api uses virtual threads).
FROM clojure:temurin-21-tools-deps-alpine

# git for the commit-per-entry store. Non-root user with FIXED uid 1000:
# infra/cloud-init.yaml chowns /srv/bbledger/data (mounted as /data) to this
# uid so append->commit keeps working; change one, change both.
RUN apk add --no-cache git && adduser -D -u 1000 bbledger && \
    mkdir /app && chown bbledger /app
USER bbledger
ENV HOME=/home/bbledger
# safe.directory: belt-and-braces for /data being a host-owned volume
RUN git config --global --add safe.directory '*'

WORKDIR /app
COPY --chown=bbledger deps.edn .
RUN clojure -P -M:bot
COPY --chown=bbledger src src
COPY --chown=bbledger resources resources

# set by CI to the release tag; "which version is running?" =
# docker exec bbledger-bot printenv BBLEDGER_VERSION (bb restart prints it)
ARG VERSION=dev
ENV BBLEDGER_VERSION=$VERSION

ENV BBLEDGER_CONFIG=/data/config.edn
CMD ["clojure", "-M:bot"]
