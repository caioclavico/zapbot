# Compilação no runner; a VM recebe somente a imagem de execução.
# Shadow-cljs exige Java 21 (o JDK da etapa final não é usado em runtime Node).
FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_SKIP_DOWNLOAD=true
WORKDIR /app
COPY package*.json ./
COPY scripts/patch-whatsapp-media.js ./scripts/patch-whatsapp-media.js
RUN npm ci
COPY shadow-cljs.edn ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d
LABEL io.zapbot.service="odisseu" io.zapbot.persistence-version="1" io.zapbot.restore-protocol="1"
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-liberation ca-certificates tini \
    && rm -rf /var/lib/apt/lists/* /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
ENV NODE_ENV=production \
    PERFORMANCE_METRICS=false \
    PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    CHROMIUM_DISABLE_GPU=true
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/target/main.js ./target/main.js
COPY package*.json ./
COPY assets/abujamra.png ./assets/abujamra.png
COPY assets/ash.png ./assets/ash.png
COPY assets/centro-pokemon.png ./assets/centro-pokemon.png
COPY assets/enfermeira-joy.png ./assets/enfermeira-joy.png
COPY assets/enfermeira-joy-tratando.png ./assets/enfermeira-joy-tratando.png
COPY assets/loja-pokemon.png ./assets/loja-pokemon.png
COPY assets/professor-carvalho.png ./assets/professor-carvalho.png
# Fase 12.7.1 — lembretes de raid automática (ovo no ginásio)
COPY assets/raid-egg-30min.png ./assets/raid-egg-30min.png
COPY assets/raid-egg-15min.png ./assets/raid-egg-15min.png
COPY assets/raid-egg-5min.png ./assets/raid-egg-5min.png
COPY scripts/lib ./scripts/lib
ENTRYPOINT ["/usr/bin/tini", "--"]
# LocalAuth usa /app/.wwebjs_auth, persistido pelo Compose. Nunca limpar locks.
COPY scripts/healthcheck.js ./scripts/healthcheck.js
HEALTHCHECK --interval=60s --timeout=10s --start-period=180s --retries=3 CMD ["node", "scripts/healthcheck.js"]
USER 1000:1000
CMD ["node", "target/main.js"]
