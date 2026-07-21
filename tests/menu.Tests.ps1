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
