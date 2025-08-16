# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Only copy pom first to leverage Docker cache for dependency download
COPY pom.xml ./
RUN mvn -q -e -B -DskipTests dependency:go-offline || mvn -q -B -DskipTests -U dependency:go-offline

# Copy source and build
COPY src ./src
RUN mvn -q -B -DskipTests clean package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# ---- Install AWS Roles Anywhere credential helper ----
# Includes bash and coreutils (timeout) used by healthcheck/entrypoint
ARG RA_HELPER_VERSION=1.7.1
RUN apt-get update \
	 && apt-get install -y --no-install-recommends \
		 ca-certificates curl bash coreutils \
	 && rm -rf /var/lib/apt/lists/* \
	 && curl -fsSL "https://rolesanywhere.amazonaws.com/releases/${RA_HELPER_VERSION}/X86_64/Linux/Amzn2023/aws_signing_helper" -o /usr/local/bin/aws_signing_helper \
	 && chmod +x /usr/local/bin/aws_signing_helper

# Copy fat jar from build stage
COPY --from=build /app/target/tokenization-service-0.0.1-SNAPSHOT.jar /app/app.jar

# Default JVM options can be overridden at runtime via JAVA_OPTS
ENV JAVA_OPTS=""

# Spring Boot port
EXPOSE 8088

# Healthcheck (basic): check the port is listening (adjust if actuator added)
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD timeout 5 bash -c '</dev/tcp/127.0.0.1/8088' || exit 1

ENTRYPOINT ["bash","-lc","exec java $JAVA_OPTS -jar /app/app.jar"]
