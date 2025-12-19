# Tasks Document

- [x] 1. Add ZIO HTTP health check route
  - File: apps/api/src/main/scala/morney/... (routing module)
  - Create `GET /healthz` route returning HTTP 200 and `{ status: "ok" }`
  - Ensure route is unauthenticated and performs no dependency checks
  - _Leverage: Existing ZIO HTTP server/router setup in apps/api (if present)_
  - _Requirements: 1.1_
  - _Prompt: Implement the task for spec api-healthz, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Scala Backend Developer with ZIO HTTP expertise | Task: Add a ZIO HTTP `GET /healthz` endpoint under apps/api that returns HTTP 200 and `{ status: "ok" }`, integrating with the existing router/server setup if present | Restrictions: Do not add authentication or external dependency checks, do not introduce new frameworks beyond ZIO HTTP, follow existing routing patterns and file structure, use package morney | _Leverage: apps/api/src/main/scala (existing ZIO HTTP router/server), build.sbt_ | _Requirements: 1.1_ | Success: `GET /healthz` responds with HTTP 200 and `{ status: "ok" }`, no auth required, no external checks, code compiles and follows project structure; mark task in-progress before coding, log implementation after completion, then mark complete in tasks.md

- [x] 2. Add tests for health check endpoint using ZIO testing
  - File: apps/api/src/test/scala/morney/... (test module)
  - Add tests verifying HTTP 200 and `{ status: "ok" }` response
  - Use ZIO Test (and ZIO HTTP test utilities if available)
  - _Leverage: Existing ZIO test setup in apps/api (if present), ZIO HTTP test utilities_
  - _Requirements: 1.1_
  - _Prompt: Implement the task for spec api-healthz, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Scala QA Engineer with ZIO testing expertise | Task: Add tests for `GET /healthz` using ZIO Test (and ZIO HTTP test utilities if available) to verify HTTP 200 and JSON body `{ status: "ok" }` | Restrictions: Do not introduce a non-ZIO test framework, keep tests isolated, follow existing test patterns | _Leverage: apps/api/src/test/scala (existing test setup), ZIO Test, ZIO HTTP test utilities_ | _Requirements: 1.1_ | Success: Health check tests pass with ZIO Test, task status updated with implementation logged
