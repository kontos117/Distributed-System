# MapReduce Platform: Testing Specifications & Documentation

This document provides comprehensive documentation on the automated testing suite for the MapReduce platform. It outlines the architecture, tools used, module-specific test coverage, and execution instructions.

## 1. Objectives & Scope
The test suite ensures the reliability, scalability, and resilience of the distributed platform as outlined in **Section 8 (Testing Specifications)** of the system design. It is designed to run completely isolated from external infrastructure (like physical Kubernetes clusters or external cloud providers) while maintaining full end-to-end realism using containerization.

## 2. Technology Stack
The testing layer leverages the following tools:
* **JUnit 5 (Jupiter)**: The core testing framework for all modules.
* **Mockito & AssertJ**: For robust object mocking and fluent assertions.
* **Spring Boot Test (`@SpringBootTest`, `MockMvc`)**: For testing REST controllers and full Spring application contexts.
* **Spring Security Test**: For mocking JWT tokens and bypassing real Keycloak instances in test contexts.
* **Testcontainers**: To programmatically spin up real PostgreSQL and MinIO Docker containers.
* **Maven Surefire Plugin**: Orchestrates the execution of tests during the build lifecycle.

---

## 3. Test Suite Breakdown

### 3.1. Unit & Controller Tests
Unit tests are designed to be extremely fast and validate core business logic without loading the full Spring Application Context. Controller tests use `MockMvc` to validate HTTP routing, security boundaries, and payload validation.

**Manager Service (`manager-service`)**
* `FileServiceTest`: Validates metadata generation and database persistence without interacting with real MinIO.
* `HeartbeatWatchdogTest`: Simulates time progression to ensure dead workers are detected and their tasks are automatically reassigned.
* `InternalTaskControllerTest` / `InternalJobControllerTest`: Validates internal routing used by Kubernetes workers.
* `JobOrchestrationCancelTest`: Ensures that in-flight jobs can be aborted and their running worker pods are terminated via the Kubernetes API.

**UI Service (`ui-service`)**
* `AuthControllerTest`: Verifies Keycloak redirect logic and session management.
* `JobControllerTest`, `DataControllerTest`, `CodeControllerTest`: Verifies that UI interactions correctly format and relay REST requests to the downstream `manager-service`.

### 3.2. Integration Tests
Integration tests run with a real Spring Context, a real database, and real object storage.

* `MinioStorageServiceIT`: Validates actual file uploads, directory listing algorithms, bucket creation, and presigned URL generation against a real MinIO container.
* `JobLifecycleIT`: Orchestrates a full end-to-end job submission. It ensures the system correctly transitions from `MAP_PHASE` -> `REDUCE_PHASE` -> `COMPLETED`.
* `WorkerFailureRecoveryIT`: The most critical integration test. It simulates worker failures during a job execution and ensures the Orchestration Service correctly retries the task 3 times. On the 4th failure, it guarantees the entire job is marked as `FAILED`.
* `TaskRepositoryIT`: Validates custom JPA queries and database constraints.

### 3.3. Chaos Engineering Tests (`ChaosEngineeringIT`)
These tests simulate catastrophic failures in the distributed system to ensure the orchestrator handles them gracefully.
* **Random Pod Killing**: Simulates 30% of worker pods randomly crashing during the map phase. Validates that the system eventually recovers via the retry mechanism.
* **Database Connection Pool Exhaustion**: Submits numerous jobs simultaneously on separate threads to ensure HikariCP connections, JPA locks, and transactions do not result in deadlocks.
* **Concurrent Task Updates**: Validates that concurrent HTTP requests from different workers finishing at the same exact millisecond do not result in lost updates or race conditions when transitioning the job phase.
* *(Disabled)* **MinIO Service Disruption**: Simulating network partitions is best handled via `Toxiproxy`. Directly stopping the singleton MinIO container is disabled as it corrupts the port mappings for subsequent tests in the JVM lifecycle.

---

## 4. Testcontainers Architecture (The Singleton Pattern)

To ensure the tests are realistic but run quickly, we use **Testcontainers** to spin up actual Docker containers during the test run.

Instead of starting and stopping a MinIO and PostgreSQL container for *every* test class (which causes massive delays, JVM overhead, and port exhaustion), we implemented the **Singleton Container Pattern**. 

In `TestContainersBase.java`, the containers are started in a static block (`static { ... }`). They are started exactly once per test session. All integration tests extend this base class and share the running containers, making the entire suite extremely fast while retaining data isolation (tests use unique UUIDs for all database entities and MinIO bucket prefixes).

---

## 5. Kubernetes Decoupling Strategy

One of the most powerful features of this test suite is that **Minikube (or any real Kubernetes cluster) does NOT need to be running**. 

We bypass Kubernetes entirely so that tests can run anywhere (like GitHub Actions) without needing a full cluster. Here is how we achieved that:

1. **Disabling K8s Auto-Config**: In `TestContainersBase.java`, we inject `kubernetes.disable.autoConfig=true`. This tells the Kubernetes Fabric8 Client to skip searching for a `.kube/config` file when Spring Boot boots up.
2. **Mocking the Launcher**: In integration tests (like `JobLifecycleIT`), we use Spring's `@MockBean` on the `KubernetesJobLauncher`. Instead of actually talking to the K8s API to spawn a pod, the mock intercepts the request and instantly returns a mock pod name.
3. **Simulating Worker Callbacks**: Since there are no real pods running, the test itself acts as the "worker". It manually pushes `TaskStatusUpdate` events (like `IN_PROGRESS`, `FAILED`, or `COMPLETED`) directly to the `JobOrchestrationService`, simulating exactly what a real pod would do via HTTP.

---

## 6. Development & Execution Requirements

### Prerequisites
* **Docker Daemon**: Must be running locally (Testcontainers requires Docker API version 1.40+).
* **Java 25**: The system compiles using JDK 25.

### Known Caveats
* **Mockito Inline Mocking on Java 25**: To support advanced mocking on JDK 25, the Maven `pom.xml` explicitly overrides the `byte-buddy` dependency to version `1.18.8`. The `maven-surefire-plugin` is also configured to export internal JVM modules (`--add-opens java.base/java.lang=ALL-UNNAMED`).
* **Docker Client API Versioning**: If you experience "client version too old" errors from Testcontainers, it is due to older daemon compatibility. We force the API version in `manager-service/src/test/resources/docker-java.properties` (`api.version=1.44`).

### Execution Commands

You can execute the test suite using standard Maven commands.

**Run All Tests (Unit, Controller, Integration, Chaos):**
```bash
./mvnw test
```

**Run Only Integration Tests:**
```bash
./mvnw test -pl manager-service -Dtest="*IT"
```

**Run Only Unit Tests:**
```bash
./mvnw test -Dtest="*Test"
```

**Run a Specific Test Class:**
```bash
./mvnw test -pl manager-service -Dtest=WorkerFailureRecoveryIT
```
