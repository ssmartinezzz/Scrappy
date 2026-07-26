# Legacy Launcher Retirement Specification

## Purpose

Defines retirement of the duplicated PowerShell/bash interactive launchers
(`interactive-cli-launcher`, PR #108) in favor of the single native CLI, and
the replacement of the security assurance those launchers' tests provided.

## Requirements

### Requirement: Legacy Launcher Files Deleted

The system MUST delete `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, and
`tests/menu_test.sh` as part of this change, since the native CLI fully
supersedes their functionality.

#### Scenario: Legacy files absent after change
- GIVEN the `native-cli-installer` change has been applied
- WHEN the repository is inspected
- THEN `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, and
  `tests/menu_test.sh` no longer exist

### Requirement: Injection-Safety Test Replacement

The system MUST include a test in the native CLI's test suite that asserts
the same security property the deleted `menu.Tests.ps1`/`menu_test.sh`
guaranteed: a hostile input string (e.g. `a"b;$(x)`) supplied as a site name
or value MUST NOT reach a shell execution context or corrupt the JSON
payload structure sent to the backend.

#### Scenario: Replacement test exists and passes
- GIVEN the native CLI's test suite
- WHEN a test constructs a site payload with input `a"b;$(x)`
- THEN the test asserts the resulting JSON is well-formed with the hostile
  string as a single field value
- AND asserts no shell subprocess was invoked with the unescaped raw string
