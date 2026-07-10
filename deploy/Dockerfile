# Build from the repo root:  docker build -f deploy/Dockerfile -t bbledger .
# JVM 21+ required (clj-tg-bot-api uses virtual threads).
FROM clojure:temurin-21-tools-deps-alpine

# git for the commit-per-entry store; safe.directory because /data is a
# host-owned volume with a different uid than the container user.
RUN apk add --no-cache git && git config --global --add safe.directory '*'

WORKDIR /app
COPY deps.edn .
RUN clojure -P -M:bot
COPY src src
COPY resources resources

ENV BBLEDGER_CONFIG=/data/config.edn
CMD ["clojure", "-M:bot"]
