# Native CLI Orchestration Specification

## Purpose

Defines the native CLI (Python + Textual, shipped as `.py` source) that owns
building the project, the build-time environment-variable export ordering
(not the `.env` generation policy itself — see `env-file-generation`), and
orchestrating backend + frontend processes with interactive control and
graceful degradation.

## Requirements

### Requirement: CLI Owns Build Steps

The CLI MUST run `npm install` followed by `npm run build` for the frontend,
and MUST run `mvn clean package` followed by copying
`scraper/target/fashion-scraper-1.0.0.jar` to `scraper/scraper.jar` for the
backend, using the toolchain already provisioned by the installer (vendored
`_tools/jdk21`, `_tools/maven`, `_tools/node`).

#### Scenario: Fresh build via CLI
- GIVEN the toolchain is provisioned and no build artifacts exist
- WHEN the CLI runs its build step
- THEN `npm install` and `npm run build` complete for the frontend
- AND `mvn clean package` completes and `scraper/scraper.jar` is refreshed
  from `target/fashion-scraper-1.0.0.jar`

### Requirement: VITE_API_BASE_URL Build-Time Export Ordering

The CLI MUST export `VITE_API_BASE_URL` as a real process environment
variable before invoking `npm run build`, because Vite bakes this value at
build time and does not read it from `.env`.

#### Scenario: Env var exported before frontend build
- GIVEN `.env` contains a computed or user-set `VITE_API_BASE_URL`
- WHEN the CLI runs the frontend build step
- THEN `VITE_API_BASE_URL` is present in the build subprocess's environment
  before `npm run build` starts

### Requirement: Backend and Frontend Process Orchestration

The CLI MUST start the backend (Tomcat on the configured port, default
`:3000`) and the frontend (`npm run preview` on `:5173`) as managed
subprocesses, and MUST perform clean teardown of both processes when the CLI
exits (normal quit, `Q`, or `Ctrl+C`).

#### Scenario: Orchestrated startup
- GIVEN the build has already completed
- WHEN the user starts the CLI
- THEN both the backend and frontend processes start and become reachable

#### Scenario: Clean teardown on exit
- GIVEN backend and frontend processes are running under the CLI
- WHEN the user quits the CLI
- THEN both subprocesses are terminated and no orphaned process remains

### Requirement: Graceful TUI Degradation

The CLI MUST degrade to a plain, non-interactive text output mode — without
crashing — when run under `NO_COLOR`, `TERM=dumb`, a non-TTY or piped
stdout/stdin, or legacy `cmd.exe` without ANSI support.

#### Scenario: Piped output falls back to plain text
- GIVEN the CLI's stdout is piped to a file or another process
- WHEN the CLI starts
- THEN it renders plain, non-interactive text output instead of the Textual
  UI
- AND it does not crash or hang waiting for terminal input

#### Scenario: NO_COLOR respected
- GIVEN the `NO_COLOR` environment variable is set
- WHEN the CLI renders output
- THEN no ANSI color codes are emitted
