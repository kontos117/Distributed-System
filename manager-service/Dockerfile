# ----- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY . .

RUN apk add --no-cache maven && \
    mvn -pl common,manager-service -am \
        -DskipTests --no-transfer-progress package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/manager-service/target/manager-service-*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
