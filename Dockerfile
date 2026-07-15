FROM eclipse-temurin:21-jre-jammy

ARG APP_UID=10001
ARG APP_GID=10001
ARG APP_VERSION=development
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="forklift-erp" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="forklift-erp"

RUN groupadd --system --gid "${APP_GID}" forklift \
    && useradd --system --uid "${APP_UID}" --gid "${APP_GID}" --home-dir /app forklift

WORKDIR /app

COPY --chown=forklift:forklift target/docker/app.jar /app/app.jar

USER forklift

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
