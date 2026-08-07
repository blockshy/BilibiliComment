# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25@sha256:52766e42de54a9a52bd72e500db1d8e8818133b79550586aa7ddac10c6e4fc84 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package && \
    cp target/bilibili-comment-*.jar /workspace/application.jar

FROM eclipse-temurin:25-jre@sha256:d0eb1b9018b3044da1b7346f39e945f71095749853d69a3aa16b8c99dad9bb45 AS runtime

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="BilibiliComment API" \
      org.opencontainers.image.source="https://github.com/blockshy/BilibiliComment" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN groupadd --gid 10001 application && \
    useradd --uid 10001 --gid application --no-create-home --shell /usr/sbin/nologin application
WORKDIR /application
COPY --from=build --chown=0:0 --chmod=0444 /workspace/application.jar ./application.jar

USER 10001:10001
EXPOSE 8111
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD ["bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/8111; printf 'GET /actuator/health/liveness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; head -n 1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["java", "-jar", "/application/application.jar"]
