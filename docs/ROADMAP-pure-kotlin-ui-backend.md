# Roadmap: Replace Spring-based db-scheduler-ui backend with a pure Kotlin/Ktor backend

Owner: db-scheduler-ui-ktor module
Status: Proposed

## Goal
Eliminate Spring Boot transitive dependencies introduced by `no.bekk.db-scheduler-ui:db-scheduler-ui` by re-implementing the minimal backend functionality (models, services, utilities, and static resources wiring) in pure Kotlin, integrated into the `db-scheduler-ui-ktor` module.

This will let users depend on `db-scheduler-ui-ktor` without pulling any Spring artifacts. It will also remove the implicit Jackson requirement, ensuring compatibility with `kotlinx-serialization` or any JSON engine installed by the host Ktor app.

## Scope Overview
- Replace usages of `no.bekk.dbscheduler.ui` (model, service, util) referenced from:
  - `routing/api.kt` (TaskLogic, LogLogic, util.Caching)
  - `routing/tasks.kt` (TaskRequestParams, TaskDetailsRequestParams, TaskLogic)
  - `routing/history.kt` (TaskDetailsRequestParams, LogLogic)
  - `routing/config.kt` (ConfigResponse)
  - `util.kt` (TaskRequestParams, TaskDetailsRequestParams conversion)
- Keep the same HTTP endpoints and JSON payload schema expected by the db-scheduler UI frontend, to avoid changes on the UI side.
- Integrate static UI resources from the upstream `db-scheduler-ui-frontend` via a Git submodule and a Gradle copy task.
- Remove the `libs.dbScheduler.ui` dependency and all related Spring transitive dependencies.

## Design and Technical Approach

### 1. Modules and Packages
Implement a new internal backend layer inside `db-scheduler-ui-ktor`:
- `io.github.osoykan.scheduler.ui.backend.model` — data classes for request/response DTOs.
- `io.github.osoykan.scheduler.ui.backend.service` — services for querying tasks and (optionally) logs.
- `io.github.osoykan.scheduler.ui.backend.util` — light utilities such as caching.

All are pure Kotlin with no Spring dependencies. The package is internal to avoid leaking API unless intentionally exported.

### 2. Serialization Strategy
- Define DTOs as Kotlin `data class`es. Annotate with `@file:UseSerializers(...)` and `@Serializable` where helpful for `kotlinx.serialization`. Use types compatible with both Jackson and kotlinx (e.g., `java.time.Instant` serialized to ISO-8601 string), or provide custom serializers for kotlinx if needed.
- Do NOT add any serialization engine in `db-scheduler-ui-ktor`. The host application will already install either Ktor’s Jackson or kotlinx-serialization plugin. The DTOs must be straightforward enough for both.

### 3. Task Data Access
- The original Spring-based backend queries db-scheduler tables directly for:
  - Listing tasks with filters, pagination, and sorting
  - Getting details for a particular task
  - Polling for updates
  - Triggering operations (rerun, rerun group, delete)
- For a pure Kotlin solution, we will:
  - Use the configured `DataSource` (already provided by `DbSchedulerUIConfiguration`) and run SQL queries directly using JDBC (no Spring). SQL schema follows db-scheduler defaults but must be configurable via `DbSchedulerUIConfiguration` if needed.
  - Where operations can be performed via the db-scheduler API (`Scheduler`), prefer that; otherwise, perform direct SQL operations guarded with transactions.
  - Keep the same response shapes as the db-scheduler-ui frontend expects. If exact schema differs per version, we will pin to a tested JSON contract.

### 4. Logs / History (Phase 3)
- The Ktor plugin currently prevents enabling history: `LogConfiguration(history=true)` errors out. This is intentional until we re-implement `LogLogic`.
- Once implemented, `LogLogic` will use the provided `DataSource` to read from the configured log table (`scheduled_execution_logs` by default), without Spring. We will implement the endpoints `/logs` and `/poll` with pagination/poll logic matching the UI expectations.

### 5. Caching
- Replace `no.bekk...util.Caching` with a tiny in-memory cache abstraction:
  - Simple thread-safe map with time-based eviction; or optional Caffeine if we decide to add a dependency (prefer no extra deps initially).
  - Scope: cache expensive aggregates like stats for a short TTL (e.g., 1–5 seconds) to reduce DB load.

### 6. Static UI Integration
- Add `db-scheduler-ui-frontend` as a Git submodule under `external/db-scheduler-ui-frontend`.
- Gradle task in `db-scheduler-ui-ktor` to copy the built frontend bundle to `src/main/resources/static/db-scheduler` during build:
  - `./external/db-scheduler-ui-frontend/db-scheduler-ui-frontend/build` → `db-scheduler-ui-ktor/src/main/resources/static/db-scheduler`
  - Wire task dependencies so that publishing includes the static resources.
- Alternatively, copy artifacts from a released tarball as a fallback (if submodules are undesired by consumers), but submodule keeps us in sync.

### 7. Backward Compatibility
- Preserve the existing API routes:
  - `/db-scheduler-api/config`
  - `/db-scheduler-api/tasks/all`
  - `/db-scheduler-api/tasks/details`
  - `/db-scheduler-api/tasks/poll`
  - `/db-scheduler-api/tasks/rerun`
  - `/db-scheduler-api/tasks/rerunGroup`
  - `/db-scheduler-api/tasks/delete`
  - (logs endpoints later)
- Preserve query parameter names and defaulting logic already implemented in `util.kt`.
- Preserve JSON field names and value formats used by the UI.

### 8. Testing Strategy
- Unit tests for:
  - DTO serialization (round-trip with kotlinx-serialization; best-effort Jackson compatibility if present in example project)
  - SQL query builders and mappers
  - Cache behavior
- Integration tests using Testcontainers (PostgreSQL) against the db-scheduler schema, verifying endpoints produce expected JSON given seeded data.
- Contract (golden file) tests to lock the JSON shape for task listing and details.

### 9. Gradle / Dependency Changes
- Remove `implementation(libs.dbScheduler.ui)` from `db-scheduler-ui-ktor/build.gradle.kts` once the replacement is complete.
- Ensure no Spring artifacts are pulled transitively. Keep dependencies lean: Ktor server core, db-scheduler core, logging. Avoid Jackson-specific libs in this module.

## Implementation Plan (Phased)

Phase 0 — Documentation and Scaffold (this PR)
- Add this roadmap and design document.
- Inventory current imports from `no.bekk.dbscheduler.ui.*` to finalize the list of DTOs and service methods to replicate.

Phase 1 — Models and Utilities
- Introduce `backend.model` DTOs: `TaskRequestParams`, `TaskDetailsRequestParams`, `ConfigResponse`, plus response DTOs used by `TaskLogic`.
- Implement `backend.util.Caching` (basic TTL map) and adjust routing to reference our model types (keeping type signatures stable at the HTTP boundary).
- Keep existing `TaskLogic`/`LogLogic` references temporarily via adapters if needed.

Phase 2 — Task Service
- Implement `backend.service.TaskService` replacing `TaskLogic`:
  - `getAllTasks`, `getTask`, `pollTasks`, `runTaskNow`, `runTaskGroupNow`, `deleteTask`.
  - Direct JDBC via `DataSource` + transactional helpers; or use db-scheduler APIs where possible.
- Replace imports in routes to point to our `TaskService` and DTOs.
- Remove `libs.dbScheduler.ui` dependency.

Phase 3 — Logs / History (optional initially)
- Implement `backend.service.LogService` akin to `LogLogic` with JDBC queries to `scheduled_execution_logs` (configurable), and wire routes `/logs` and `/poll`.
- Relax the guard in `DbSchedulerUIConfiguration.LogConfiguration`.

Phase 4 — Static UI Integration
- Add the frontend Git submodule and Gradle copy task to embed static resources into the Ktor module resources path used by `singlePageApplication`.

Phase 5 — Tests and Hardening
- Add integration tests using Testcontainers to validate queries and JSON payloads.
- Add contract tests with golden JSON snapshots for main endpoints.

Phase 6 — Release
- Bump version. Note BREAKING CHANGES: removal of Spring transitive deps; JSON should remain compatible. Document migration steps (none for consumers except possible JSON engine differences, which should be neutral).

## Migration Notes for Users
- No changes to how `DbSchedulerUI` plugin is installed.
- Spring dependencies will no longer be included transitively.
- The module will not require Jackson; if your app uses `kotlinx.serialization`, it will work as long as you register Ktor’s `ContentNegotiation` with `kotlinx`.
- If you relied on any Spring classes leaking through (unlikely), you’ll need to add them explicitly.

## Open Questions / Decisions
- Exact DB schema version alignment with db-scheduler: provide configurability for table names and columns if the schema differs by version.
- Whether to introduce Caffeine for caching (decide after basic impl; default to no extra dependency).
- Contract of datetime serialization (prefer ISO-8601 strings to match UI and cross-serializer compatibility).

---

Appendix A — Current External References to Replace
- `no.bekk.dbscheduler.ui.model.*`:
  - TaskRequestParams, TaskDetailsRequestParams, ConfigResponse, and response DTOs used by TaskLogic
- `no.bekk.dbscheduler.ui.service.*`:
  - TaskLogic (Phase 2 replacement), LogLogic (Phase 3)
- `no.bekk.dbscheduler.ui.util.Caching` → `backend.util.Caching`

Appendix B — Milestones
1. Phase 1 merged: internal DTOs + caching in place, no behavior change
2. Phase 2 merged: Task service switched, Spring deps removed
3. Phase 3 merged: Optional logs/history
4. Phase 4 merged: UI submodule integration
5. Phase 5 merged: Tests hardened; release
