# Compilação no runner; a VM recebe somente a imagem de execução.
FROM node:22-bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends default-jre-headless ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_SKIP_DOWNLOAD=true
WORKDIR /app
COPY package*.json ./
COPY scripts/patch-whatsapp-media.js ./scripts/patch-whatsapp-media.js
RUN npm ci
COPY shadow-cljs.edn ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-liberation ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production \
    PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    CHROMIUM_DISABLE_GPU=true
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/target/main.js ./target/main.js
COPY package*.json ./
COPY assets ./assets
ENTRYPOINT ["/usr/bin/tini", "--"]
# LocalAuth usa /app/.wwebjs_auth, persistido pelo Compose. Nunca limpar locks.
COPY scripts/healthcheck.js ./scripts/healthcheck.js
HEALTHCHECK --interval=60s --timeout=10s --start-period=180s --retries=3 CMD ["node", "scripts/healthcheck.js"]
CMD ["node", "target/main.js"]
