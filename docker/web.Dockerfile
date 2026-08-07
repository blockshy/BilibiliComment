# syntax=docker/dockerfile:1.7
FROM node:22-alpine@sha256:4d64b49e6c891c8fc821007cb1cdc6c0db7773110ac2c34bf2e6960adef62ed3 AS build

ARG VCS_REF=unknown
ENV VITE_APP_VERSION=${VCS_REF}

WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend ./
RUN npm run lint && npm test && npm run build

FROM nginx:1.30.1@sha256:842a3f99afd73859b5c647f8be6f0000849be286674e30d9dbcf7a6902a69487 AS runtime

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="BilibiliComment Web" \
      org.opencontainers.image.source="https://github.com/blockshy/BilibiliComment" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

COPY docker/web-nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=101:101 /workspace/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/healthz"]
ENTRYPOINT ["nginx", "-g", "daemon off;"]
