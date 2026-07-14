# ADR-001: Java 17 and compatible platform

- Status: Proposed
- Date: 2026-07-14
- Requirements: CON-002, CON-004, NFR-030

## Context

The code must stay Java 17-compatible, while the normal local build JVM is JDK
21. JDK 21 can compile and run Java 17 code; accidental Java 21 API use is
prevented by the compiler release and a final Java 17 compatibility run, not by
blocking the local build JVM.

## Decision

Use:

- Java 17 language/API target with `maven.compiler.release=17`;
- Spring Boot 4.1.0;
- Spring Cloud 2025.1.2;
- Spring Cloud CircuitBreaker 5.0.2 and its managed Resilience4j 2.3.0;
- test-scoped `org.wiremock:wiremock-standalone:3.13.2` for deterministic
  Gateway dependency fixtures without Jetty dependency collisions;
- Maven Wrapper pinned to Maven 3.9.16 (current GA when checked on 2026-07-14)
  and Java 17 runtime container images.

Do not use Java 21 APIs, virtual threads, or Resilience4j 3.x. Resilience4j 3.x requires Java 21 and conflicts with this baseline.

The root POM must enforce `maven.compiler.release=17` and accept Boot 4.1's
supported JDK range 17 through 26. Normal development may run Maven on JDK 21.
CI/container runtime and one final local compatibility run use Java 17.

## Why

Spring Boot 4.1 still supports Java 17, so Java 17 does not require selecting an
old framework line. Using the Spring Cloud BOM avoids an improvised collection
of incompatible dependency versions. WireMock's standard 3.13.2 artifact uses
Jetty 11 while Boot 4.1 manages Jetty 12. The official standalone artifact shades
most dependencies, so it is the safer in-process test fixture here; no Spring
Boot downgrade is needed. WireMock 4.x remains beta and is not selected for the
core implementation.

## Consequences

- Code examples and technical explanations stay on records, sealed types where useful, pattern matching supported by Java 17, standard executors, and ordinary Spring MVC.
- Boot 4.1's default JSON stack is Jackson 3: new databind/tree code uses
  `tools.jackson.*`; deprecated Jackson 2 databind imports are rejected.
- An AI-generated use of `Thread.ofVirtual`, Java 21 collection methods, or a 3.x Resilience4j version is a review failure.
- Local JDK 21 `test`/`verify` proves the normal workflow; a Java 17 compatibility
  run proves the emitted bytecode and dependencies really work on the minimum.

## Verification

```bash
java -version
./mvnw -version
./mvnw test
./mvnw verify

export JAVA_HOME="${JAVA_HOME_17:?Set JAVA_HOME_17 to an installed JDK 17}"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test
./mvnw verify
```

Record the JDK 21 normal run and the Java 17 compatibility run separately.
