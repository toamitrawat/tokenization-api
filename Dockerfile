# syntax=docker/dockerfile:1
# JAR is built by Jenkins (mvnw clean verify) before docker build runs.
# Dockerfile is intentionally single-stage — packaging only, no compilation.

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY target/tokenization-service-*.jar /app/app.jar

# Default JVM options can be overridden at runtime via JAVA_OPTS
ENV JAVA_OPTS=""

# Spring Boot port
EXPOSE 8088

# Actuator liveness probe — wget is pre-installed in eclipse-temurin (Ubuntu-based)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8088/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
