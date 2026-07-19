# ScriptHub

Spring Boot API for asynchronous JavaScript execution on GraalVM with output
available during execution.

## Tech Stack

- Java 21
- Spring Boot 3.5
- GraalVM Polyglot
- SpringDoc OpenAPI
- Gradle
- Lombok
- JUnit 5

## Requirements

- Oracle GraalVM for JDK 21 — the JDK distribution required for sandboxed script
  execution with polyglot isolates.

The build includes a platform-specific GraalVM isolate. Build the application
separately for each target operating system and architecture.

Supported build platforms:

- Windows x86-64
- Linux x86-64
- Linux ARM64
- macOS ARM64

## Execution Sandbox

User-supplied JavaScript runs in an isolated GraalVM context without access to
host APIs, the filesystem, processes, or additional guest threads. The runtime
also limits memory, CPU time, execution time, stack depth, and output size.

Scripts run as embedded JavaScript, not as Node.js or browser applications.
Exceeding a sandbox resource limit fails the execution.

Default execution and sandbox limits are defined under `script-hub.execution`
in [`application.yaml`](src/main/resources/application.yaml) and can be
overridden through standard Spring Boot configuration.

## Run

The project uses the included Gradle wrapper; no local Gradle installation is
required.

Windows:

```powershell
.\gradlew.bat bootRun
```

Unix-like:

```shell
./gradlew bootRun
```

OpenAPI documentation is available at [Swagger UI](http://localhost:8080/swagger-ui.html).

## Build

Windows:

```powershell
.\gradlew.bat build
```

Unix-like:

```shell
./gradlew build
```
