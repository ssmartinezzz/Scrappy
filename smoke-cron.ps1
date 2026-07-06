#Requires -Version 5.1
<#
.SYNOPSIS
    Smoke test for the /api/cron backend (scraper-cronjobs PR1, task 7.2).

.DESCRIPTION
    Exercises the full cron REST surface against a RUNNING server:
    create / list / get / update / delete, plus the validation paths
    (invalid cronExpr -> 400, missing fields -> 400, unknown id -> 404).

    The test job is created DISABLED with a far-future cronExpr, so the
    30s poller never auto-fires it while the test runs.

    run-now is gated behind -RunNow because it launches a REAL scrape
    (Playwright + ML). When enabled, it scrapes a single site to keep the
    run light, asserts 202 then 409 (busy), and checks an execution row.

.PARAMETER BaseUrl
    Server base URL. Default http://localhost:3000.

.PARAMETER RunNow
    Also test POST /api/cron/{id}/run-now. WARNING: starts a real scrape.

.EXAMPLE
    ./smoke-cron.ps1
    ./smoke-cron.ps1 -RunNow
    ./smoke-cron.ps1 -BaseUrl http://localhost:3000 -RunNow
#>
param(
    [string]$BaseUrl = 'http://localhost:3000',
    [switch]$RunNow
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

# Invoke an endpoint and return @{ Status = <int>; Body = <object|null> }.
# Captures non-2xx responses (400/404/409) instead of throwing, so they can
# be asserted. Written for Windows PowerShell 5.1 (no -SkipHttpErrorCheck).
function Invoke-Api {
    param(
        [Parameter(Mandatory)] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        $Body
    )
    $params = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body        = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    try {
        $r = Invoke-WebRequest @params
        $parsed = $null
        if ($r.Content) { try { $parsed = $r.Content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$r.StatusCode; Body = $parsed }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { throw }
        $reader  = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $content = $reader.ReadToEnd()
        $parsed  = $null
        if ($content) { try { $parsed = $content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$resp.StatusCode; Body = $parsed }
    }
}

function Check {
    param([string]$Name, [bool]$Cond, [string]$Detail = '')
    if ($Cond) {
        Write-Host "  PASS  $Name" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red
        $script:fail++
    }
}

Write-Host "`nSmoke test /api/cron @ $BaseUrl`n" -ForegroundColor Cyan

# 0. Server reachable ---------------------------------------------------------
try {
    $status = Invoke-Api GET '/api/status'
    Check 'server is up (GET /api/status -> 200)' ($status.Status -eq 200)
} catch {
    Write-Host "  FAIL  server unreachable at $BaseUrl - is it running?" -ForegroundColor Red
    Write-Host "        start it with INSTALAR_Y_CORRER.bat, then re-run.`n" -ForegroundColor Yellow
    exit 1
}

# 1. Create (disabled, far-future cron so the poller never fires it) ----------
$newJob = @{
    name         = 'SMOKE-TEST-cron'
    precioMin    = 1000
    precioMax    = 50000
    sitios       = @()          # [] = all sites (server semantics)
    forceRetrain = $false
    useGpu       = $true
    cronExpr     = '0 0 3 1 1 *' # 03:00 on Jan 1 - effectively never during a test
    enabled      = $false
}
$create = Invoke-Api POST '/api/cron' $newJob
Check 'create job (POST /api/cron -> 200)' ($create.Status -eq 200) "got $($create.Status)"
$jobId = $create.Body.id
Check 'create returns a numeric id' ($jobId -is [int] -or $jobId -is [long] -or $jobId -gt 0) "id=$jobId"
Check 'create echoes the name back' ($create.Body.name -eq 'SMOKE-TEST-cron')
Check 'disabled job has null nextRunAt' ($null -eq $create.Body.nextRunAt)

# 2. Validation: invalid cronExpr -> 400 --------------------------------------
$badCron = $newJob.Clone(); $badCron.cronExpr = 'no soy un cron'
$r = Invoke-Api POST '/api/cron' $badCron
Check 'invalid cronExpr -> 400' ($r.Status -eq 400) "got $($r.Status)"

# 3. Validation: missing required field -> 400 --------------------------------
$noName = $newJob.Clone(); $noName.Remove('name')
$r = Invoke-Api POST '/api/cron' $noName
Check 'missing name -> 400' ($r.Status -eq 400) "got $($r.Status)"

# 4. List contains our job ----------------------------------------------------
$list = Invoke-Api GET '/api/cron'
Check 'list (GET /api/cron -> 200)' ($list.Status -eq 200)
Check 'list contains the created job' (@($list.Body.jobs | Where-Object { $_.id -eq $jobId }).Count -eq 1)

# 5. Get one ------------------------------------------------------------------
$got = Invoke-Api GET "/api/cron/$jobId"
Check 'get one (GET /api/cron/{id} -> 200)' ($got.Status -eq 200)
Check 'get one returns the right job' ($got.Body.id -eq $jobId -and $got.Body.name -eq 'SMOKE-TEST-cron')

# 6. Get unknown -> 404 -------------------------------------------------------
$r = Invoke-Api GET '/api/cron/999999999'
Check 'get unknown id -> 404' ($r.Status -eq 404) "got $($r.Status)"

# 7. Update -------------------------------------------------------------------
$upd = $newJob.Clone(); $upd.precioMax = 77777
$r = Invoke-Api PUT "/api/cron/$jobId" $upd
Check 'update (PUT /api/cron/{id} -> 200)' ($r.Status -eq 200)
Check 'update persisted precioMax' ([double]$r.Body.precioMax -eq 77777) "got $($r.Body.precioMax)"

# 8. Update unknown -> 404 ----------------------------------------------------
$r = Invoke-Api PUT '/api/cron/999999999' $upd
Check 'update unknown id -> 404' ($r.Status -eq 404) "got $($r.Status)"

# 9. Executions list (empty before any run) -----------------------------------
$ex = Invoke-Api GET "/api/cron/$jobId/executions"
Check 'executions (GET /api/cron/{id}/executions -> 200)' ($ex.Status -eq 200)
Check 'executions is an array' ($null -ne $ex.Body.executions)

# 10. run-now (opt-in; launches a real scrape) --------------------------------
if ($RunNow) {
    Write-Host "`n  -RunNow: launching a REAL scrape (single site to keep it light)..." -ForegroundColor Yellow

    # Narrow the job to a single site so the smoke scrape stays cheap.
    $sitios = Invoke-Api GET '/api/sitios'
    $firstSite = ($sitios.Body | Select-Object -First 1)
    $siteName = if ($firstSite.nombre) { $firstSite.nombre } else { $firstSite }
    if ($siteName) {
        $narrow = $upd.Clone(); $narrow.sitios = @($siteName)
        Invoke-Api PUT "/api/cron/$jobId" $narrow | Out-Null
        Write-Host "        scoped run-now to site: $siteName" -ForegroundColor DarkGray
    }

    $run1 = Invoke-Api POST "/api/cron/$jobId/run-now"
    Check 'run-now -> 202 (started)' ($run1.Status -eq 202) "got $($run1.Status)"

    Start-Sleep -Milliseconds 500
    $run2 = Invoke-Api POST "/api/cron/$jobId/run-now"
    Check 'run-now again while busy -> 409' ($run2.Status -eq 409) "got $($run2.Status)"

    $ex2 = Invoke-Api GET "/api/cron/$jobId/executions"
    Check 'an execution row was recorded' (@($ex2.Body.executions).Count -ge 1)

    Write-Host "        NOTE: a real scrape is now running in the background." -ForegroundColor Yellow
    Write-Host "        After it finishes, manually confirm the global price range" -ForegroundColor Yellow
    Write-Host "        was restored (GET /api/data meta precioMin/precioMax) and that" -ForegroundColor Yellow
    Write-Host "        the execution row has logOutput populated." -ForegroundColor Yellow
} else {
    Write-Host "`n  (skipping run-now - pass -RunNow to test it; it starts a real scrape)" -ForegroundColor DarkGray
}

# 11. Delete ------------------------------------------------------------------
$r = Invoke-Api DELETE "/api/cron/$jobId"
Check 'delete (DELETE /api/cron/{id} -> 200)' ($r.Status -eq 200)
$r = Invoke-Api DELETE "/api/cron/$jobId"
Check 'delete again -> 404' ($r.Status -eq 404) "got $($r.Status)"

# Summary ---------------------------------------------------------------------
Write-Host "`n----------------------------------------" -ForegroundColor Cyan
Write-Host ("  {0} passed, {1} failed" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "----------------------------------------`n" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
