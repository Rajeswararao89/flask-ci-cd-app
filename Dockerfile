# Dockerfile – multi-stage build for a Java/Maven application.
# Swap the builder stage for your actual tech stack (Node, Go, Python, etc.)

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache Maven dependencies separately so they survive layer cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build the application
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Use a minimal JRE image – no JDK, no build tools, smaller attack surface.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as a non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the built artifact from the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Expose the application port (must match containerPort in deployment.yaml)
EXPOSE 8080

# Health check – Docker will mark the container unhealthy if this fails.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
