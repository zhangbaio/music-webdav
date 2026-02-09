# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Spring Boot 2.7 (Java 8) microservice that scans WebDAV music libraries, extracts audio metadata (title, artist, album, duration, etc.), and provides REST APIs for searching/querying the music catalog. Uses Sardine for WebDAV access and jaudiotagger for audio tag parsing.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Run the application (requires MySQL on localhost:3306, database: music_webdav)
mvn spring-boot:run

# Run with local profile (H2 in-memory, no MySQL needed)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Run a single test class
mvn test -Dtest=MetadataFallbackServiceTest

# Run a single test method
mvn test -Dtest=MetadataFallbackServiceTest#testFallback
```

## Architecture

Four-layer DDD-inspired structure under `com.example.musicwebdav`:

- **`api/`** — REST controllers + request/response DTOs. Three controllers: `WebDavController` (config CRUD, connection test), `ScanTaskController` (create/query scan tasks), `TrackController` (search/detail).
- **`application/service/`** — Business orchestration. `FullScanService` is the core: recursively traverses WebDAV directories, downloads audio files to temp storage, parses metadata via a thread pool, and batch-inserts into MySQL. `ScanTaskService` manages the task state machine (PENDING → RUNNING → SUCCESS/FAILED/PARTIAL_SUCCESS).
- **`domain/`** — Pure domain models and enums. No framework dependencies. `TrackRepository` interface lives here (implemented by infrastructure).
- **`infrastructure/`** — Technical adapters: `SardineWebDavClient` (WebDAV), `JaudiotaggerAudioMetadataParser` (audio parsing), MyBatis mappers + entities, `TokenAuthFilter` (Bearer token auth).
- **`common/`** — Cross-cutting: config properties (`AppScanProperties`, `AppSecurityProperties`, `AppWebDavProperties`), global exception handler, `AesCryptoUtil` (AES/GCM for encrypting WebDAV passwords), `AccessLogFilter`.

## Key Technical Details

- **Database**: MySQL 5.7+ in production, H2 in-memory with `local` profile. Schema managed by Liquibase (changelogs in `src/main/resources/db/changelog/`).
- **ORM**: MyBatis with annotation-based mappers in `infrastructure/persistence/mapper/`.
- **Auth**: Bearer token via `TokenAuthFilter`. Token configured in `app.security.api-token`. Actuator and Swagger endpoints are public.
- **Server port**: 18080 (configurable via `SERVER_PORT` env var).
- **API docs**: Swagger UI at `/swagger-ui.html`, OpenAPI spec at `/v3/api-docs`.
- **Scan config**: Batch size, thread count, retry policy, supported audio extensions all configurable under `app.scan.*` in application.yml.
- **WebDAV passwords**: Encrypted with AES/GCM before storage. Key set via `app.security.encrypt-key`.

## Database Tables

Five tables: `webdav_config`, `scan_task`, `scan_task_item`, `scan_task_seen_file`, `track`. Track uniqueness is enforced by composite index on `(source_config_id, source_path_md5)`.

## Design Documents

Detailed PRD and technical design docs are in `docs/`:
- `webdav-music-scan-requirements.md` — Functional/non-functional requirements
- `webdav-music-scan-development-design.md` — Architecture, DB schema, API design, state machine
- `webdav-music-scan-task-priority-plan.md` — Implementation roadmap
