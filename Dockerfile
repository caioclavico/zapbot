# Imagem pensada para ARM64 (ex.: Oracle Cloud Free Tier - Ampere A1),
# onde o Puppeteer não tem Chromium pré-compilado: usamos o Chromium do
# sistema (apt) em vez do download automático do Puppeteer.
FROM node:20-bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      chromium \
      fonts-liberation \
      ca-certificates \
      tini \
      default-jre-headless \
    && rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

# NODE_ENV=production só depois do build: setado antes, o npm install pula
# devDependencies (shadow-cljs) e o build falha com "shadow-cljs: not found"
ENV NODE_ENV=production

# tini evita processos zumbis do Chromium ao encerrar o container
ENTRYPOINT ["/usr/bin/tini", "--"]
# limpa o lock do Chromium (SingletonLock) antes de iniciar: como o volume
# .wwebjs_auth persiste entre deploys mas o hostname do container muda a
# cada `docker run`, o Chromium acha que o lock é de "outra máquina" e se
# recusa a destravar sozinho, travando o start em todo redeploy
CMD ["sh", "-c", "find /app/.wwebjs_auth -iname 'Singleton*' -delete 2>/dev/null; exec npm start"]
