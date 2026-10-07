# Multi-stage build for AMPS Backend
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Copy gradle files
COPY backend/gradle backend/gradle
COPY backend/gradlew backend/gradlew
COPY backend/build.gradle.kts backend/build.gradle.kts
COPY backend/settings.gradle.kts backend/settings.gradle.kts
COPY backend/gradle.properties backend/gradle.properties

# Download dependencies
RUN cd backend && ./gradlew dependencies --no-daemon

# Copy source
COPY backend/src backend/src

# Build fat jar
RUN cd backend && ./gradlew shadowJar --no-daemon

# ===== Runtime stage =====
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Copy fat jar
COPY --from=builder /app/backend/build/libs/amps-backend.jar /app/amps-backend.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/amps-backend.jar"]