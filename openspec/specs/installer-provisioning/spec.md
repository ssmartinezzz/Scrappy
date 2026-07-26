# Installer Provisioning Specification

## Purpose

Defines the reduced scope of `INSTALAR_Y_CORRER.bat` (Windows) and
`Ejecutar_instalar.sh` (Linux; macOS is out of scope) after
`native-cli-installer`: they provision the toolchain only and hand off to the
native CLI for build, `.env` generation, and run. This spec does not restate
the currently-verified provisioning steps (JDK, Maven, Node, Python
embeddable, portable Postgres, ML deps) — those steps are preserved as-is;
only the boundary changes.

## Requirements

### Requirement: Installer Scope Restricted to Dependency Provisioning

The installers MUST provision the toolchain — JDK, Maven, Node, the Python
3.11 embeddable interpreter with pip and site-packages, portable PostgreSQL,
and the ML dependency set (torch/sklearn/transformers/Marqo) — and MUST NOT
perform any project build step (`npm install`, `npm run build`,
`mvn clean package`, jar copy) and MUST NOT generate or modify the root
`.env` file.

#### Scenario: Installer run provisions dependencies only
- GIVEN a clean machine with no toolchain installed
- WHEN `INSTALAR_Y_CORRER.bat` (or `Ejecutar_instalar.sh`) runs to completion
- THEN the toolchain is provisioned under `_tools/`
- AND no `npm run build`, `mvn package`, or `.env` write occurred

#### Scenario: Installer tail invokes the CLI
- GIVEN toolchain provisioning has completed successfully
- WHEN the installer script reaches its final step
- THEN it invokes the native CLI (vendored Python interpreter running the CLI
  `.py` entry point) instead of building or launching services itself

### Requirement: Installer/CLI Boundary Invariant

The system MUST preserve the invariant that the installer never builds the
project and the CLI never downloads or installs a toolchain component.

#### Scenario: CLI does not provision toolchain
- GIVEN the CLI is running standalone (installer already completed
  previously)
- WHEN the CLI performs build or run steps
- THEN it MUST NOT download, install, or modify any toolchain component
  under `_tools/`

### Requirement: Python Load-Bearing on Windows

On Windows, if the provisioned Python embeddable interpreter fails to
install, fails pip bootstrap, or fails to run, the installer MUST fail the
install with a clear, actionable error message identifying the failed step,
instead of continuing silently with ML disabled.

#### Scenario: Python provisioning fails on Windows
- GIVEN the Python embeddable download or pip bootstrap fails during
  `INSTALAR_Y_CORRER.bat`
- WHEN the installer detects the failure
- THEN it aborts the install with a message naming the failed step and a
  suggested remedy
- AND it does not continue to build or run the project

#### Scenario: Linux behavior unchanged
- GIVEN `Ejecutar_instalar.sh` already hard-fails when `python3` is missing
- WHEN the installer runs on Linux
- THEN this existing hard-fail behavior is preserved
