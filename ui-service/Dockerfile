# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy entire project, Maven reactor needs all module POMs present
COPY . .

RUN apk add --no-cache maven && \
    mvn -pl common,ui-service -am \
        -DskipTests --no-transfer-progress package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/ui-service/target/ui-service-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
