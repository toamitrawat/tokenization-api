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

# Install bash and coreutils (timeout) used by healthcheck/entrypoint
RUN apt-get update \
	 && apt-get install -y --no-install-recommends \
		 bash coreutils \
	 && rm -rf /var/lib/apt/lists/*

# Copy fat jar from build stage
COPY --from=build /app/target/tokenization-service-0.0.1-SNAPSHOT.jar /app/app.jar

# Default JVM options can be overridden at runtime via JAVA_OPTS
ENV JAVA_OPTS=""

# Spring Boot port
EXPOSE 8088

# Healthcheck (basic): check the port is listening (adjust if actuator added)
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD timeout 5 bash -c '</dev/tcp/127.0.0.1/8088' || exit 1

ENTRYPOINT ["bash","-lc","exec java $JAVA_OPTS -jar /app/app.jar"]
