# knurd container image. Build from the repository root, after building knurd:
#
#   ./gradlew :knurd:installDist
#   docker build -f knurd/Dockerfile -t knura/knurd:dev .      (or podman build ...)
#
# Only knurd/build/install/knurd goes into the image (see .dockerignore): no
# knurd.properties, keys or counters from your computer. Settings come in at run time, from
# /etc/knura/knurd.properties (the Helm chart mounts it).
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${JRE_IMAGE}

# A fixed, unprivileged user, so Kubernetes can check runAsNonRoot and file ownership.
RUN addgroup -S -g 10001 knura && adduser -S -D -H -u 10001 -G knura knura
COPY knurd/build/install/knurd /opt/knurd

USER 10001:10001
EXPOSE 8424
# -XX:-UsePerfData: no shared-memory file in /tmp, so the root filesystem can stay read-only.
ENV JAVA_OPTS="-XX:-UsePerfData -XX:MaxRAMPercentage=75"
ENTRYPOINT ["/opt/knurd/bin/knurd"]
CMD ["/etc/knura/knurd.properties"]
