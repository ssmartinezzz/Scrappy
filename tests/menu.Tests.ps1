# Pester tests for menu.ps1 — interactive-cli-launcher (RED case from the
# design threat matrix: "Shell command composition" boundary).
#
# Run with:  powershell -NoProfile -ExecutionPolicy Bypass -Command
#            "Invoke-Pester -Script tests/menu.Tests.ps1"
#
# menu.ps1 is dot-sourced with $env:MENU_TEST_MODE = '1' so its top-level
# main loop / process spawning never runs during tests — only functions are
# loaded into scope.

$env:MENU_TEST_MODE = '1'
$menuPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'menu.ps1'
. $menuPath

Describe "Build-SiteJson (safe JSON body building)" {

    It "produces a literal JSON value for hostile input, without shell evaluation" {
        # Threat matrix case: user-typed nombre/url with quotes, semicolons,
        # and command-substitution-looking syntax must reach the API as a
        # literal string — never interpolated into a shell command or
        # hand-built JSON string.
        $hostile = 'a"b;$(x)'
        $json = Build-SiteJson -Nombre $hostile -Url 'https://example.com' -Plataforma 'tiendanube'

        # Must be valid, parseable JSON (proves ConvertTo-Json escaped it).
        $parsed = $json | ConvertFrom-Json

        $parsed.nombre | Should Be $hostile
        $parsed.url | Should Be 'https://example.com'
        $parsed.plataforma | Should Be 'tiendanube'
    }

    It "defaults plataforma to tiendanube when not provided" {
        $json = Build-SiteJson -Nombre 'MiMarca' -Url 'https://mimarca.com'
        $parsed = $json | ConvertFrom-Json
        $parsed.plataforma | Should Be 'tiendanube'
    }

    It "never throws for input containing subexpression-looking syntax (no eval)" {
        { Build-SiteJson -Nombre '$(Remove-Item C:\should-not-run)' -Url 'https://x.com' } | Should Not Throw
    }
}

Describe "Get-ApiErrorMessage (surfaces the API's real mensaje on error)" {

    It "extracts 'mensaje' from ErrorDetails.Message JSON body (400 validation case)" {
        # Mirrors what Invoke-RestMethod's catch block sees in PS 5.1 for a
        # 400 from POST /api/sitios: ErrorDetails.Message holds the raw
        # response body, e.g. {"ok":false,"mensaje":"nombre y url son obligatorios"}
        $fakeError = [pscustomobject]@{
            ErrorDetails = [pscustomobject]@{ Message = '{"ok":false,"mensaje":"nombre y url son obligatorios"}' }
            Exception    = [pscustomobject]@{ Message = 'The remote server returned an error: (400) Bad Request.' }
        }
        Get-ApiErrorMessage $fakeError | Should Be 'nombre y url son obligatorios'
    }

    It "falls back to the raw exception message when the body isn't JSON" {
        $fakeError = [pscustomobject]@{
            ErrorDetails = $null
            Exception    = [pscustomobject]@{ Message = 'Unable to connect to the remote server' }
        }
        Get-ApiErrorMessage $fakeError | Should Be 'Unable to connect to the remote server'
    }
}

Describe "Import-DotEnv (standalone .env sourcing)" {

    It "sets a variable that isn't already in the process environment" {
        $tmp = Join-Path $env:TEMP ("dotenv_test_{0}.env" -f ([guid]::NewGuid().ToString('N')))
        "MENU_TEST_DBURL=jdbc:postgresql://127.0.0.1:5432/scraper" | Set-Content -LiteralPath $tmp
        Remove-Item Env:MENU_TEST_DBURL -ErrorAction SilentlyContinue
        try {
            Import-DotEnv $tmp
            $env:MENU_TEST_DBURL | Should Be 'jdbc:postgresql://127.0.0.1:5432/scraper'
        } finally {
            Remove-Item $tmp -ErrorAction SilentlyContinue
            Remove-Item Env:MENU_TEST_DBURL -ErrorAction SilentlyContinue
        }
    }

    It "never clobbers a var the parent process already set" {
        $tmp = Join-Path $env:TEMP ("dotenv_test_{0}.env" -f ([guid]::NewGuid().ToString('N')))
        "MENU_TEST_KEEP=from_dotenv" | Set-Content -LiteralPath $tmp
        $env:MENU_TEST_KEEP = 'from_parent'
        try {
            Import-DotEnv $tmp
            $env:MENU_TEST_KEEP | Should Be 'from_parent'
        } finally {
            Remove-Item $tmp -ErrorAction SilentlyContinue
            Remove-Item Env:MENU_TEST_KEEP -ErrorAction SilentlyContinue
        }
    }

    It "ignores comments and blank lines without throwing" {
        $tmp = Join-Path $env:TEMP ("dotenv_test_{0}.env" -f ([guid]::NewGuid().ToString('N')))
        @('# a comment', '', 'MENU_TEST_OK=1') | Set-Content -LiteralPath $tmp
        Remove-Item Env:MENU_TEST_OK -ErrorAction SilentlyContinue
        try {
            { Import-DotEnv $tmp } | Should Not Throw
            $env:MENU_TEST_OK | Should Be '1'
        } finally {
            Remove-Item $tmp -ErrorAction SilentlyContinue
            Remove-Item Env:MENU_TEST_OK -ErrorAction SilentlyContinue
        }
    }
}
