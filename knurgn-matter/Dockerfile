# knurgn-matter container image. Build from this folder:
#
#   docker build -t knura/knurgn-matter:dev knurgn-matter      (or podman build ...)
#
# Only package*.json and src/ go into the image (see .dockerignore): no settings, secret or
# Matter storage from your computer. The Helm chart runs it next to knurd in the same pod,
# because knurgN only sends taps to a bridge on 127.0.0.1.
ARG NODE_IMAGE=node:22-alpine
FROM ${NODE_IMAGE}

WORKDIR /opt/knurgn-matter
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY src ./src

# A fixed, unprivileged user (the same one as knurd's image).
RUN addgroup -S -g 10001 knura && adduser -S -D -H -u 10001 -G knura knura
USER 10001:10001
ENTRYPOINT ["node", "src/main.js"]
CMD ["--config", "/etc/knura/knurgn-matter.properties"]
