# MapReduce UI Service

Web UI and API gateway service for the distributed MapReduce system. Provides an interactive dashboard for job submission, data management, and code upload, along with REST API endpoints for programmatic access.

## Overview

The UI Service is a Spring Boot 3.3.4 application that:
- Serves the web dashboard on `/` (static HTML/CSS/JavaScript)
- Exposes REST APIs for job submission, data file uploads, and code artifact discovery
- Acts as an API gateway, proxying requests to downstream manager-service
- Integrates with Keycloak for OAuth2/OIDC authentication and authorization
- Provides code jar auto-detection and optional Maven build assistance

## Architecture & Topology

```
┌──────────────────────────────────────────────────────┐
│ Browser / CLI Client                                 │
└────────────────┬─────────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────────┐
│ UI Service (Port 8080)                               │
├──────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────┐  │
│ │ Web Layer (Controllers)                         │  │
│ │ ├─ AuthController (session/login)              │  │
│ │ ├─ JobController (submit/list/status)          │  │
│ │ ├─ DataController (upload input files)         │  │
│ │ ├─ CodeController (upload code jars + assist) │  │
│ │ ├─ AdminController (cluster management)        │  │
│ │ └─ GlobalExceptionHandler                      │  │
│ └─────────────────────────────────────────────────┘  │
│ ┌─────────────────────────────────────────────────┐  │
│ │ Service Layer                                   │  │
│ │ ├─ CodeAssistService (jar discovery, builds)   │  │
│ │ └─ (Other domain services)                      │  │
│ └─────────────────────────────────────────────────┘  │
│ ┌─────────────────────────────────────────────────┐  │
│ │ HTTP Clients                                    │  │
│ │ ├─ ManagerClient (→ manager-service:8081)     │  │
│ │ └─ KeycloakAdminClient (→ keycloak:8080)      │  │
│ └─────────────────────────────────────────────────┘  │
│ ┌─────────────────────────────────────────────────┐  │
│ │ Web Content                                     │  │
│ │ ├─ static/index.html (main dashboard)          │  │
│ │ └─ static/* (CSS, JS, assets)                  │  │
│ └─────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
         ▲              │              │
         │              ▼              ▼
    Auth │        Proxy │         Proxy │
         │              ▼              ▼
    ┌────┴──────┐  ┌──────────────────────────┐
    │ Keycloak  │  │ Manager Service          │
    │ :30180    │  │ (Port 8081)              │
    └───────────┘  ├──────────────────────────┤
                   │ Orchestrates workers     │
                   │ Manages job scheduling   │
                   │ Stores metadata (DB)     │
                   └──────────────────────────┘
                        │         ▲
                        ▼         │
                   ┌──────────────────────────┐
                   │ Worker Nodes             │
                   │ (Running MapReduce jobs) │
                   └──────────────────────────┘
```

## Prerequisites

- **Java 21** (JDK for building, JRE for running)
- **Maven 3.8+** (for building from source)
- **Keycloak** (for authentication, running at `http://keycloak:8080` or `http://192.168.49.2:30180`)
- **Manager Service** (running at `http://manager-service:8081` or configured via `MANAGER_BASE_URL`)
- **Workspace access** (for Maven builds of example modules)

## Installation

### Build from Source

```bash
cd /home/marios/Distributed-Systems

# Build ui-service and its dependencies
mvn -pl ui-service -am clean package -DskipTests

# Or build the full system
mvn clean package -DskipTests
```

The built JAR will be at:
```
ui-service/target/ui-service-1.0.0-SNAPSHOT.jar
```

### Docker Build

```bash
# From the workspace root
docker build -f ui-service/Dockerfile -t mapreduce-ui-service:latest .
```

## Running the Service

### Locally (Direct JAR)

```bash
export KEYCLOAK_ISSUER_URI="http://192.168.49.2:30180/realms/mapreduce"
export KEYCLOAK_ADMIN_URL="http://192.168.49.2:30180"
export KEYCLOAK_ADMIN_USERNAME="admin"
export KEYCLOAK_ADMIN_PASSWORD="admin-secret"
export MANAGER_BASE_URL="http://localhost:8081"

java -jar ui-service/target/ui-service-1.0.0-SNAPSHOT.jar
```

The service will start on **http://localhost:8080**

### Docker Container

```bash
docker run -d \
  -p 8080:8080 \
  -e KEYCLOAK_ISSUER_URI="http://keycloak:8080/realms/mapreduce" \
  -e KEYCLOAK_ADMIN_URL="http://keycloak:8080" \
  -e KEYCLOAK_ADMIN_USERNAME="admin" \
  -e KEYCLOAK_ADMIN_PASSWORD="admin-secret" \
  -e MANAGER_BASE_URL="http://manager-service:8081" \
  --name ui-service \
  mapreduce-ui-service:latest
```

### Kubernetes

```bash
kubectl apply -f k8s/ui/ui-deployment.yaml
kubectl apply -f k8s/ui/ui-configmap.yaml

# Check deployment
kubectl -n mapreduce get pods,svc -l app=ui-service
```

## Configuration

### Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `KEYCLOAK_ISSUER_URI` | `http://keycloak:8080/realms/mapreduce` | Keycloak realm URL for JWT validation |
| `KEYCLOAK_ADMIN_URL` | `http://keycloak:8080` | Keycloak admin console URL |
| `KEYCLOAK_ADMIN_USERNAME` | `admin` | Keycloak admin user |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin-secret` | Keycloak admin password |
| `KEYCLOAK_CLIENT_ID` | `mapreduce-cli` | OAuth2 client ID |
| `KEYCLOAK_REALM` | `mapreduce` | Keycloak realm name |
| `MANAGER_BASE_URL` | `http://manager-service:8081` | Manager service base URL |

All variables are sourced from environment at runtime. See `src/main/resources/application.yml` for property mapping.

### File Uploads

The service accepts file uploads up to **1GB** (configurable in `application.yml`):
- Data files: `/api/v1/data/upload` (max 1GB per file)
- Code JARs: `/api/v1/code/upload` (max 1GB per file)

## Health & Status

### Health Check

```bash
curl http://localhost:8080/actuator/health
# Response: { "status": "UP", "components": {...} }
```

### Prometheus Metrics

```bash
curl http://localhost:8080/actuator/prometheus
# Prometheus-formatted metrics
```

### OpenAPI / Swagger

```
http://localhost:8080/swagger-ui.html
```

Full OpenAPI spec at `/v3/api-docs`

## REST API Endpoints

### Job Management

- `GET /api/v1/jobs` - List all jobs
- `POST /api/v1/jobs` - Submit new job
- `GET /api/v1/jobs/{id}` - Get job details
- `PUT /api/v1/jobs/{id}` - Update job status
- `DELETE /api/v1/jobs/{id}` - Cancel job

### Data Uploads

- `POST /api/v1/data/upload` - Upload input data file
- `GET /api/v1/data/files` - List uploaded files
- `GET /api/v1/data/files/{id}` - Download file

### Code Management

- `POST /api/v1/code/upload` - Upload code JAR
- `GET /api/v1/code/assist/candidates` - Discover built JARs in workspace
- `POST /api/v1/code/assist/candidates/{id}/upload` - Use detected JAR
- `POST /api/v1/code/assist/build?module={module}` - Build example module via Maven

### Admin

- `POST /api/v1/admin/cluster/start` - Start cluster
- `POST /api/v1/admin/cluster/stop` - Stop cluster
- `GET /api/v1/admin/cluster/status` - Cluster status
- `GET /api/v1/admin/logs` - System logs

### Authentication

- `GET /auth/login` - Redirect to Keycloak login
- `GET /auth/logout` - Logout
- `GET /auth/callback` - OAuth2 callback (handled internally)

## Key Components

### Controllers

| Class | Purpose |
|-------|---------|
| `AuthController` | OAuth2 login/logout flow, session management |
| `JobController` | Job submission, listing, status updates |
| `DataController` | Input file uploads and retrieval |
| `CodeController` | Code JAR uploads, artifact discovery, build assistance |
| `AdminController` | Cluster operations (start/stop/status) |
| `GlobalExceptionHandler` | Centralized error handling & response formatting |

### Services

| Class | Purpose |
|-------|---------|
| `CodeAssistService` | JAR discovery (filesystem walk), Maven build execution, artifact validation |

### HTTP Clients

| Class | Purpose |
|-------|---------|
| `ManagerClient` | REST client for manager-service communication (job creation, metadata fetch) |
| `KeycloakAdminClient` | REST client for Keycloak admin operations |

### Configuration

| Class | Purpose |
|-------|---------|
| `SecurityConfig` | OAuth2 resource server setup, JWT validation, CORS, endpoint authorization |
| `WebClientConfig` | Spring RestClient bean configuration for HTTP clients |
| `OpenApiConfig` | Swagger/OpenAPI documentation setup |

## Development

### Project Structure

```
ui-service/
├── src/main/
│   ├── java/gr/tuc/distributed/ui/
│   │   ├── UiApplication.java                 (Main entry point)
│   │   ├── controller/                        (REST endpoints)
│   │   ├── service/                           (Business logic)
│   │   ├── client/                            (External API clients)
│   │   └── config/                            (Spring configuration)
│   └── resources/
│       ├── application.yml                    (Configuration)
│       ├── static/
│       │   ├── index.html                     (Main dashboard)
│       │   └── *.css, *.js                    (Assets)
│       └── db/
│           └── migration/                     (Flyway SQL scripts)
├── pom.xml                                   (Maven dependencies)
├── Dockerfile                                (Multi-stage container build)
└── README.md                                 (This file)
```

### Building with Hot Reload (Development)

```bash
# Terminal 1: Start Spring Boot DevTools (auto-reload on file changes)
mvn -pl ui-service spring-boot:run

# Terminal 2: Watch and rebuild static assets (if using build tool)
# Or simply refresh browser after editing HTML/CSS/JS
```

### Running Tests

```bash
mvn -pl ui-service test
```

## Code Split Compliance

This service is owned by Marios (Frontend/UI) as per `code_split_plan.md` with 30% ownership of frontend UI and controllers. All backend modifications remain scoped to this module to avoid conflicts with other teams' code.

## Troubleshooting

### Service won't start
- **Check Keycloak is reachable**: `curl -i http://192.168.49.2:30180`
- **Check MANAGER_BASE_URL**: `curl -i http://manager-service:8081/actuator/health`
- **Check port 8080**: `lsof -i :8080` (kill conflicting process if needed)

### JAR detection returns empty list
- **Ensure examples are built**: `mvn -pl examples clean package -DskipTests`
- **Check workspace structure**: `find examples -name "*.jar" | grep target`
- **Clear cache**: Service rescans on every request; no caching

### Build assist fails
- **Ensure Maven is installed**: `which mvn`
- **Check module exists**: Must be one of `wordcount|loggrep|arraylookup`
- **Review build output**: Response includes last 120 lines of Maven build log

### Authentication issues
- **Check token expiry**: Keycloak tokens expire after ~15 minutes; login again
- **Verify issuer-uri**: Must match exactly: `http://192.168.49.2:30180/realms/mapreduce`
- **Check scope**: Ensure OAuth2 client `mapreduce-cli` has proper realm roles

## License

Part of the distributed MapReduce system. See LICENSE in workspace root.
