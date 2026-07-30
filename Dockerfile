# Imagem pensada para ARM64 (ex.: Oracle Cloud Free Tier - Ampere A1),
# onde o Puppeteer não tem Chromium pré-compilado: usamos o Chromium do
# sistema (apt) em vez do download automático do Puppeteer.
FROM node:20-bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      chromium \
      fonts-liberation \
      ca-certificates \
      tini \
    && rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    NODE_ENV=production

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

# tini evita processos zumbis do Chromium ao encerrar o container
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["npm", "start"]
