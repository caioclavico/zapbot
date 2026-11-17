# Compilação no runner; a VM recebe somente a imagem de execução.
# Shadow-cljs exige Java 21 (o JDK da etapa final não é usado em runtime Node).
FROM eclipse-temurin:21-jdk-jammy AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs=22.23.4-1nodesource1 \
    && test "$(node --version)" = "v22.23.4" \
    && rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_SKIP_DOWNLOAD=true
WORKDIR /app
COPY package*.json ./
COPY scripts/patch-whatsapp-media.js ./scripts/patch-whatsapp-media.js
RUN npm ci
COPY shadow-cljs.edn ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22.23.4-bookworm-slim@sha256:aaa4b5baf1ecff3b283486bb1f90405cdda225a3e6f79660d2c559fa510a7292
# Browser: Debian Chromium (não Google Chrome).
# Produção operacional (eefacb92) usa pacote `chromium` 155; a RC e7488e4f usava
# `google-chrome-stable` 157 e autenticou/loading 100% sem READY (RECOVERING),
# com whatsapp-startup + package-lock idênticos. O 155 saiu do apt; pin no
# sucessor security `156.0.8078.79-1~deb12u1` (mesma família Debian chromium).
LABEL io.zapbot.service="odisseu" \
      io.zapbot.persistence-version="1" \
      io.zapbot.restore-protocol="1" \
      io.zapbot.runtime.node="22.23.4" \
      io.zapbot.runtime.chromium="156.0.8078.79-1~deb12u1" \
      io.zapbot.runtime.brotli="1.0.9-2+deb12u1" \
      io.zapbot.runtime.browser-package="chromium"
RUN printf '%s\n' 'deb http://security.debian.org/debian-security bookworm-security main' \
      > /etc/apt/sources.list.d/bookworm-security.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
      chromium=156.0.8078.79-1~deb12u1 \
      chromium-common=156.0.8078.79-1~deb12u1 \
      chromium-sandbox=156.0.8078.79-1~deb12u1 \
      libbrotli1=1.0.9-2+deb12u1 \
      fonts-liberation ca-certificates tini \
    && test -x /usr/bin/chromium \
    && ! test -e /usr/bin/google-chrome-stable \
    && rm -rf /usr/local/lib/node_modules/npm \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx \
    && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production \
    PERFORMANCE_METRICS=false \
    PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    CHROMIUM_DISABLE_GPU=true
WORKDIR /app
RUN test "$(node --version)" = "v22.23.4"
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/target/main.js ./target/main.js
COPY package*.json ./
COPY assets ./assets
COPY scripts/lib ./scripts/lib
# O drain de produção tem de viajar com a imagem. Falha o build se sumir
# ou se o bundle voltar a desligar o sandbox do Chromium.
RUN node -e 'const fs=require("fs"); const files=["scripts/lib/a6-maintenance.cjs","scripts/lib/a6-shutdown.cjs"]; for (const f of files) { if (!fs.existsSync(f)) { console.error("missing "+f); process.exit(1); } } const js=fs.readFileSync("target/main.js","utf8"); const banned=["--no-"+"sandbox","--disable-"+"setuid-sandbox"]; for (const flag of banned) { if (js.indexOf(flag) !== -1) { console.error("sandbox flags reappeared in target/main.js"); process.exit(1); } }'
ENTRYPOINT ["/usr/bin/tini", "--"]
# LocalAuth usa /app/.wwebjs_auth, persistido pelo Compose. Nunca limpar locks.
COPY scripts/healthcheck.js ./scripts/healthcheck.js
HEALTHCHECK --interval=60s --timeout=10s --start-period=180s --retries=3 CMD ["node", "scripts/healthcheck.js"]
RUN install -d -o node -g node \
      /app/.wwebjs_auth /app/.wwebjs_auth_core /app/.wwebjs_auth_staging /app/data
USER 1000:1000
CMD ["node", "target/main.js"]
