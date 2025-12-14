# Task 1 Implementation Log

## Summary

Created a minimal Scala 3 + ZIO + SQLite project scaffold with a ZIO-based CLI entrypoint, configurable DB path, and a JDBC connection helper.

## Artifacts

### Build / Project
- `build.sbt`: Scala 3.3.4, ZIO, sqlite-jdbc, ZIO Test
- `project/build.properties`: sbt version pin

### CLI
- `src/main/scala/morney/ui/CliApp.scala`
  - Commands: `help`, `doctor`
  - Parses DB path from `--db` or `MORNEY_DB_PATH`

### Configuration
- `src/main/scala/morney/AppConfig.scala`
  - `AppConfig(dbPath: Path)`
  - Default DB path: `~/.morney/morney.db`

### Storage Connection
- `src/main/scala/morney/storage/Db.scala`
  - Opens SQLite JDBC connection
  - Enables `PRAGMA foreign_keys=ON`

### Tests
- `src/test/scala/morney/SmokeSpec.scala`

### Docs / Hygiene
- `README.md` updated with `sbt` run commands and DB configuration
- `.gitignore` updated for Scala build/editor artifacts and local DB folder

