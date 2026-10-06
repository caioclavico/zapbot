# node e sharp publicam variantes amd64 e arm64. Build nativo em cada plataforma.
FROM node:22-bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends default-jre-headless ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY shadow-cljs.edn ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
LABEL io.zapbot.service="pokemon" io.zapbot.persistence-version="1"
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates fonts-liberation tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/target/domain.cjs ./target/domain.cjs
COPY runtime ./runtime
COPY scripts/healthcheck.cjs ./scripts/healthcheck.cjs
COPY assets ./assets
COPY package*.json ./
RUN mkdir /app/data && chown node:node /app/data
USER node
ENV PERFORMANCE_METRICS=false NODE_ENV=production HOST=0.0.0.0 PORT=8090 DATA_DIR=/app/data
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s CMD ["node", "scripts/healthcheck.cjs"]
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "runtime/main.cjs"]
