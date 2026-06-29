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

- JDK 21

GraalVM JDK 21 is preferred for script execution performance.

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
