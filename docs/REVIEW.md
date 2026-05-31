# FastCache Multi-Project Code Review

This document summarizes findings from a review of the Gradle multi-project build (root project and the health-checker subproject) and selected source files. The focus is on build/dependency management, packaging, runtime implications, logging, testing, and security/version hygiene. Actionable recommendations are provided per area.

## Overview

- Root project mixes a Netty-based server and Spring Boot web components but does not apply the Spring Boot Gradle plugin. The jar task is configured to zipTree all runtime dependencies, effectively producing a fat jar; a separate `fatJar` task duplicates this behavior.
- The `health-checker` subproject correctly applies the Spring Boot plugin and builds a bootJar, but it also explicitly declares logging and Jackson dependencies already provided and managed by Boot.
- There is dependency/version duplication and misalignment across modules (explicit versions in build.gradle vs gradle.properties; Boot-managed vs pinned versions), which increases the risk of classpath conflicts and CVEs.
- Distribution packaging includes files that appear to be missing (e.g., LICENSE), and resource merging is suppressed via `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`, which can hide problems rather than solve them.

## Project structure

- Root project (FastCache) contains:
  - Netty server: `com.fastcache.server.FastCacheServer` (main entrypoint)
  - Spring Boot application: `com.fastcache.discovery.ServiceDiscoveryAPI` (annotated with `@SpringBootApplication`)
- Subproject `health-checker` contains:
  - Spring Boot application: `com.fastcache.health.CentralizedHealthCheckerService`

Observation: Root module combines a Netty server and a Spring Boot web app with Boot starters on the classpath but without the Spring Boot plugin. This increases build complexity and artifact size, and can cause runtime conflicts. Consider separating Spring Boot apps into dedicated subprojects.

## Build and dependency management

Root project (`/workspace/build.gradle`):
- Plugins: `java`, `application`, `jacoco` — no Spring Boot plugin.
- Dependencies include:
  - Netty: `io.netty:netty-all:4.1.100.Final` (heavy umbrella artifact; includes everything)
  - Jackson: `com.fasterxml.jackson.core:jackson-databind:2.15.2` (explicit version)
  - Logging: `org.slf4j:slf4j-api:2.0.9`, `ch.qos.logback:logback-classic:1.4.11` (explicit)
  - Spring Boot starters: `spring-boot-starter-web:3.2.0`, `spring-boot-starter-actuator:3.2.0` added without the Boot Gradle plugin or BOM
  - Testing: JUnit and Mockito explicitly plus `spring-boot-starter-test:3.2.0`

Issues:
- Mixing Boot starters with non-Boot build: Without `org.springframework.boot` plugin or `io.spring.dependency-management`, Boot dependency versions are manually pinned and may not be consistent with each other. This can result in version misalignment (e.g., Boot 3.2.0 typically manages Jackson 2.15.3; the build pins 2.15.2).
- Duplicated logging: Boot’s `spring-boot-starter-web` pulls in `spring-boot-starter-logging` (Logback and SLF4J binding). Adding `slf4j-api` and `logback-classic` explicitly duplicates bindings and risks conflicts.
- Duplicated testing libs: `spring-boot-starter-test` already includes JUnit Jupiter and Mockito; explicit `junit-jupiter` and `mockito` versions can cause version clashes.
- Version declarations: `gradle.properties` defines versions (e.g., nettyVersion, jacksonVersion), but the build files use literal versions. This duplicates configuration and invites drift.
- Netty: `netty-all` is discouraged in favor of specific modules or BOM; it increases footprint and may include unnecessary code.

Health-checker (`/workspace/health-checker/build.gradle`):
- Plugins: `org.springframework.boot` 3.2.0 and `io.spring.dependency-management` 1.1.4 applied.
- Dependencies include Boot starters, but also explicit `jackson-databind:2.15.2`, `slf4j-api:2.0.9`, and `logback-classic:1.4.11`.

Issues:
- Redundant dependencies with Boot: Explicit Jackson and Logback/SLF4J duplicates Boot-managed ones. This undermines the BOM and increases the risk of dependency convergence problems.
- Task choice: A custom `runHealthChecker` JavaExec task is defined instead of using Boot’s `bootRun`, which is more appropriate and supports layered jars and devtools.

Recommendations:
- Decide on a clear split: either
  - Move all Spring Boot apps (ServiceDiscoveryAPI and health-checker) into dedicated Boot-enabled subprojects, and remove Boot starters from the root project; or
  - Apply Spring Boot plugin and BOM to the root project if you intend to package a Boot app there (not recommended given the Netty server role).
- If keeping Boot only in subprojects:
  - Remove `spring-boot-starter-web/actuator/test` from the root project dependencies.
  - Remove explicit `slf4j-api`, `logback-classic`, and `jackson-databind` from the root (if any Spring Boot usage remains elsewhere, manage through BOM).
- In health-checker:
  - Remove explicit `jackson-databind`, `slf4j-api`, and `logback-classic` — rely on Boot starters and BOM.
  - Prefer `bootRun` for local runs instead of a JavaExec task.
- Adopt dependency management:
  - Use Boot’s BOM via `io.spring.dependency-management` or the Spring Boot Gradle plugin to align versions.
  - Alternatively, use a Gradle Version Catalog (libs.versions.toml) to centralize versioning and avoid duplication with gradle.properties.
- Netty:
  - Replace `netty-all` with specific modules needed (e.g., `netty-transport`, `netty-codec`, `netty-handler`) and/or import the Netty BOM to align versions.

## Packaging and distribution

Root project packaging:
- `jar` task includes the entire `runtimeClasspath` via `zipTree`, effectively creating a fat jar. The build also declares a separate `fatJar` task that does the same work, and a `distJar` for a thin jar.
- `duplicatesStrategy = DuplicatesStrategy.EXCLUDE` is set to suppress duplicates across merged dependency jars.
- `createDistribution` copies `fatJar`, `README.md`, and `LICENSE` into `dist/<name>-<version>` and then copies `src/main/resources` into `config/`.

Issues:
- Duplicate fat-jar creation: Both `jar` and `fatJar` produce fat artifacts, leading to confusion and potential duplication.
- Resource collisions: Blindly unzipping all dependencies can lead to resource conflicts (META-INF entries, Logback configs, `module-info.class`, etc.). Suppressing duplicates can mask real issues (e.g., missing service files).
- Spring Boot packaging: If any Boot app is meant to be runnable from the root artifact, the current approach is incompatible with Boot’s launcher expectations (no spring-boot-loader, wrong manifest entries). Boot jars should be produced by the Boot plugin (`bootJar`), not by `zipTree`.
- Missing LICENSE: The repository appears to lack `/workspace/LICENSE`; copying it in `createDistribution` will fail the task.

Recommendations:
- Choose a single fat-jar mechanism in the root, if needed: Prefer the Shadow plugin (`com.github.johnrengelman.shadow`) or disable fat packaging entirely and ship thin jars plus `lib/` directory via the application plugin.
- If packaging Spring Boot apps: Use `bootJar` and avoid manual `zipTree`. For thin jars, keep `jar` thin and do not embed dependencies.
- Consider producing distributions with the Gradle `application` plugin and `installDist`/`distZip` tasks to create a structured layout (bin/, lib/, conf/).
- Fix distribution inputs: ensure LICENSE exists or remove it from the copy spec; verify resource copying does not override Boot configs.
- Avoid `DuplicatesStrategy.EXCLUDE` as a global fix; instead, rely on proper packaging tools that merge service files correctly (Shadow has `mergeServiceFiles`).

## Logging

- Root includes `slf4j-api` and `logback-classic`, and also pulls Boot’s `spring-boot-starter-logging` via `spring-boot-starter-web`. This can result in multiple bindings and version convergence issues.
- Health-checker includes explicit logging artifacts on top of Boot-managed ones.

Recommendations:
- If using Boot, rely on `spring-boot-starter-logging` and remove explicit `logback-classic` and `slf4j-api` from health-checker.
- In the root (Netty server), include only `slf4j-api` and a chosen binding (e.g., `logback-classic`) — but do not combine with Boot logging on the same classpath if the root is not a Boot app.
- Define a single Logback configuration file and avoid multiple conflicting configurations.

## Testing configuration

- Root `test` task enables JUnit Platform and sets logging events; also sets global `tasks.withType(Test)`:
  - `maxParallelForks = Runtime.runtime.availableProcessors().intdiv(2) ?: 1`
  - `forkEvery = 100`
- `gradle.properties` also defines `testMaxParallelForks=4` and `testForkEvery=100`, but these are not wired into the build script, creating duplication/confusion.
- Root includes both `spring-boot-starter-test:3.2.0` and explicit JUnit/Mockito dependencies. Health-checker includes both `spring-boot-starter-test` and explicit JUnit.

Recommendations:
- Use one source of truth for test parallelism/fork settings. Either reference properties or compute them dynamically, but not both.
- Avoid redundant testing dependencies: if Boot starter test is present, remove explicit JUnit/Mockito unless you need a newer version for a specific reason (then override via BOM or dependency constraints).
- Consider setting per-module test configurations (Netty server vs Boot apps) based on their needs.

## Security and version hygiene

- Versions are hardcoded in build.gradle while similar values exist in gradle.properties, leading to drift.
- `netty-all` and Jackson 2.15.2 may have known CVEs depending on point releases; Boot 3.2.0 aligns on newer Jackson than the one declared.

Recommendations:
- Align versions using BOMs (Spring Boot BOM, Netty BOM) or a Gradle Version Catalog to centralize and pin versions.
- Add automated vulnerability scanning to CI (OWASP Dependency-Check, Snyk, or GitHub Dependabot with security alerts).
- Keep dependencies up to date by enabling automated PRs (Renovate or Dependabot) and leveraging the Spring Boot plugin’s dependency management.

## Health-checker subproject review

- Boot plugin and dependency management are correctly applied.
- `bootJar` sets `archiveClassifier = 'fat'` and a custom manifest with `Start-Class` — Boot already sets the start class; the classifier is unusual and can lead to confusion (Boot’s bootJar is already a fat jar).
- `jar { enabled = false }` is appropriate for Boot apps to avoid producing a thin jar.
- `runHealthChecker` is a `JavaExec` task — for Boot apps, prefer `bootRun`, which integrates with Boot classpath handling and devtools.
- Application code:
  - Uses `@EnableScheduling` and `@Scheduled` in `CentralizedHealthCheckerService` — good.
  - Manually defines a `TomcatServletWebServerFactory` bean and binds to port 8080; this would be cleaner via `server.port=8080` in `application.properties`.
  - Shutdown endpoint calls `System.exit(0)` — not recommended in Boot; prefer graceful `ApplicationContext` shutdown.

Recommendations:
- Remove explicit logging and Jackson dependencies; rely on Boot starters and BOM.
- Use `bootRun` for local runs instead of JavaExec.
- Move port/address configuration to `application.properties` (or environment) and remove manual `ServletWebServerFactory` bean unless a custom container is needed.
- Replace `System.exit(0)` with `context.close()` or `SpringApplication.exit()` for graceful shutdown.
- Expose health endpoints via Actuator rather than custom `/health` endpoints where appropriate; or complement custom endpoints with Actuator readiness/liveness checks.

## ServiceDiscoveryAPI in root module

- `ServiceDiscoveryAPI` is a Spring Boot app in the root module with Boot starters on the classpath but no Boot plugin. The root jar is being built as a fat jar via `zipTree`, which is not compatible with Boot’s launcher and may produce runtime/resource conflicts.
- It manually configures a Tomcat factory at port 8081 and uses `SpringApplication` in `main`.

Recommendations:
- Move `ServiceDiscoveryAPI` into its own subproject (e.g., `service-discovery`) that applies the Spring Boot plugin and uses `bootJar`.
- Remove Boot starters from the root module to keep the Netty server lean and avoid classpath bloat.
- Use `server.port=8081` in properties instead of defining a Tomcat bean unless you need custom container configuration.

## CI/CD and build hygiene

- Consider enforcing code quality via Checkstyle/SpotBugs/PMD and a formatting plugin (Spotless).
- Add reproducible build settings and metadata (e.g., `Build-Jdk-Spec`, `Implementation-Vendor-Id`) in manifests if needed.
- Add integration tests that exercise the fat jar/distribution build to catch packaging issues.
- Configure `jacoco` across subprojects (`subprojects { apply plugin: 'jacoco' }`) and publish combined coverage reports if needed.

## Prioritized actionable next steps

1. Separate Boot apps into dedicated subprojects and remove Boot starters from the root. Introduce a new subproject for `ServiceDiscoveryAPI` with the Spring Boot plugin.
2. Replace manual fat-jar construction with either:
   - Shadow plugin for the Netty server (if you truly need a single runnable jar), or
   - Thin jar + application plugin distribution (preferred for clarity and maintainability).
3. In `health-checker`, remove explicit `jackson-databind`, `slf4j-api`, and `logback-classic`, and use `bootRun`. Move server.port config to `application.properties`. Implement graceful shutdown.
4. Adopt BOMs/Version Catalog and remove duplicated version properties. Align Jackson and Netty versions with managed BOMs. Remove redundant testing dependencies.
5. Fix `createDistribution` to not copy a missing LICENSE file; verify resource copying and avoid suppressing duplicates as a workaround.
6. Add vulnerability scanning and code quality tools to CI.

## References

- Spring Boot Gradle Plugin: https://docs.spring.io/spring-boot/docs/current/gradle-plugin/reference/htmlsingle/
- Shadow plugin: https://imperceptiblethoughts.com/shadow/
- Netty BOM: https://netty.io/wiki/using-the-netty-bom.html
- Spring Boot Logging: https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#features.logging
