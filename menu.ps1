# ============================================================================
# API CONTRACT - Fashion Scraper Argentina backend (Java/Spring, port 3000)
#
# This block is the canonical param/response reference for BOTH menu.ps1 and
# menu.sh (design D1 - shared action-contract table, D8 - param-drift
# mitigation). Source of truth: docs/API_REFERENCE.md and
# scraper/src/main/java/ar/scraper/web/ApiController.java. Keep this block
# byte-identical between the two scripts; if drift is suspected at runtime,
# run the `--selftest` action.
#
#   GET  /api/status
#     -> { status: IDLE|RUNNING|DONE|ERROR, mensaje, tieneData, total,
#          mlRefinadas?, mlModeloActivo?, extractionStats? }
#
#   POST /api/scrape?precioMin=<double>&precioMax=<double>
#                    &sitios=<string>&sitios=<string>...&forceRetrain=<bool>
#     all params optional/repeatable; -> { iniciado: bool, mensaje }
#     iniciado:false means a scrape is already running - NOT an error.
#
#   POST /api/ml/entrenar?images=<bool>&epochs=<int>
#     -> 200 { status: "started" }
#     -> 400 { error: "Entrenamiento ya en curso" } (pre-check)
#     -> 409 { error: "Entrenamiento ya en curso" } (race lost)
#     Both 400 and 409 mean "already running" - NOT a failure to surface as
#     an error.
#
#   GET  /api/ml/estado
#     -> { hasTextModel, hasImageModel, training: { running, phase, pct,
#          msg, startedAt }, embeddingsCount, totalProductos, coveragePct }
#
#   GET  /api/sitios
#     -> { base: [{nombre,url,tipo,rubro}], extras: [{nombre,url,plataforma}],
#          precioMinimo, precioMaximo, moneda }
#
#   POST /api/sitios   Body (JSON): { nombre, url, plataforma? = "tiendanube" }
#     -> 200 { ok: bool, mensaje }  (400 if nombre/url blank)
#     JSON body MUST be built via a safe serializer (ConvertTo-Json here,
#     jq -n --arg in menu.sh) - NEVER string-interpolated. See threat matrix
#     in design.md, "Shell command composition" boundary.
#
#   DELETE /api/sitios/{nombre}
#     -> { ok: bool, mensaje }  (ok:false when the site name is unknown)
# ============================================================================

[CmdletBinding()]
param(
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# ----------------------------------------------------------------------------
# Configuration. When launched from INSTALAR_Y_CORRER.bat these come from the
# parent process environment; when menu.ps1 runs STANDALONE they don't exist
# yet, so we resolve the repo root first and source the installer-generated
# .env (spec "Installer-generated .env sourced by launcher") before anything
# else. Without this the backend fail-fasts on missing DATABASE_URL and the
# readiness poll waits ~60s on a process that already died.
# ----------------------------------------------------------------------------
$RepoRoot = if ($env:ROOT) { $env:ROOT } else { $PSScriptRoot }

function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $name  = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        # Never clobber a var the launcher/parent process already set.
        if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
Import-DotEnv (Join-Path $RepoRoot '.env')

$ApiBase      = if ($env:VITE_API_BASE_URL) { $env:VITE_API_BASE_URL } else { 'http://localhost:3000' }
$FrontendPort = 5173
$FrontendUrl  = "http://localhost:$FrontendPort"

$ProjectDir  = if ($env:PROJECT) { $env:PROJECT } else { Join-Path $RepoRoot 'scraper' }
$JarPath     = if ($env:JAR) { $env:JAR } else { Join-Path $ProjectDir 'scraper.jar' }
# Prefer the bundled JDK 21: a system `java` may be an older major that cannot
# run the Java 21 fat JAR (UnsupportedClassVersionError -> instant backend
# death -> silent 60s hang). An explicit JAVA_EXE (set by the .bat) still wins.
$BundledJava = Join-Path $RepoRoot '_tools\jdk21\bin\java.exe'
$JavaExe     = if ($env:JAVA_EXE) { $env:JAVA_EXE } elseif (Test-Path $BundledJava) { $BundledJava } else { 'java' }
$PythonExe   = $env:PYTHON_EXE
$PythonDir   = $env:PYTHON_DIR
$FrontendDir = if ($env:FRONTEND_DIR) { $env:FRONTEND_DIR } else { Join-Path $RepoRoot 'frontend' }
$NodeDir     = if ($env:NODE_DIR) { $env:NODE_DIR }
               elseif (Test-Path (Join-Path $RepoRoot '_tools\node\npm.cmd')) { Join-Path $RepoRoot '_tools\node' }
               else { $null }
$NpmCmd      = if ($NodeDir -and (Test-Path (Join-Path $NodeDir 'npm.cmd'))) { Join-Path $NodeDir 'npm.cmd' } else { 'npm' }

$Global:BackendProcess  = $null
$Global:FrontendProcess = $null
$Global:BackendReady    = $false
$Global:FrontendReady   = $false
$Global:BackendLog      = $null

# ----------------------------------------------------------------------------
# D2 - Safe JSON body building. Builds a .NET object graph and lets
# ConvertTo-Json serialize/escape it - user-supplied strings are NEVER
# interpolated into a hand-built JSON string or a shell command, so quotes,
# semicolons, and "$()"-looking sequences reach the API as inert literal
# text. See tests/menu.Tests.ps1 for the RED case this protects.
# ----------------------------------------------------------------------------
function Build-SiteJson {
    param(
        [Parameter(Mandatory)] [string]$Nombre,
        [Parameter(Mandatory)] [string]$Url,
        [string]$Plataforma = 'tiendanube'
    )
    $body = [ordered]@{
        nombre     = $Nombre
        url        = $Url
        plataforma = $Plataforma
    }
    return ($body | ConvertTo-Json -Compress)
}

# ----------------------------------------------------------------------------
# D3 - Bounded readiness poll (2s interval, 30 attempts, ~60s cap).
# ----------------------------------------------------------------------------
function Wait-ForUrl {
    param(
        [Parameter(Mandatory)] [string]$Url,
        [int]$MaxAttempts = 30,
        [int]$DelaySeconds = 2
    )
    for ($i = 1; $i -le $MaxAttempts; $i++) {
        try {
            $resp = Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5
            return $resp
        } catch {
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    return $null
}

function Wait-Backend {
    # Same bound as Wait-ForUrl (30 x 2s) but aborts the moment the backend
    # process exits, so a backend that dies on boot (e.g. Postgres down, or an
    # UnsupportedClassVersionError) surfaces in ~seconds instead of ~60s of
    # polling a corpse. The caller inspects HasExited to print the real error.
    for ($i = 1; $i -le 30; $i++) {
        if ($Global:BackendProcess -and $Global:BackendProcess.HasExited) {
            $Global:BackendReady = $false
            return $null
        }
        try {
            $resp = Invoke-RestMethod -Uri "$ApiBase/api/status" -Method Get -TimeoutSec 5
            $Global:BackendReady = $true
            return $resp
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    $Global:BackendReady = $false
    return $null
}

function Wait-Frontend {
    # Vite preview returns HTML (not JSON), so Invoke-RestMethod's JSON
    # parser would throw on a perfectly healthy response — probe with
    # Invoke-WebRequest instead, bounded the same way as Wait-Backend
    # (D3: 2s interval, 30 attempts, ~60s cap).
    for ($i = 1; $i -le 30; $i++) {
        try {
            $resp = Invoke-WebRequest -Uri $FrontendUrl -Method Get -TimeoutSec 5 -UseBasicParsing
            $Global:FrontendReady = $true
            return $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    $Global:FrontendReady = $false
    return $false
}

# ----------------------------------------------------------------------------
# D4/D5 - Process lifecycle: spawn with -PassThru (PID capture), tear down
# via taskkill /T /F (kills the process tree - Vite preview may spawn a
# child node process) tolerating already-dead processes.
# ----------------------------------------------------------------------------
function Start-Backend {
    if (-not (Test-Path $JarPath)) {
        Write-Host "  [WARN] No se encontro $JarPath - el backend no se inicio." -ForegroundColor Yellow
        return $null
    }
    $argList = @('-Xmx768m', '-Dfile.encoding=UTF-8')
    # RequiredEnvVarsGuard requires DATABASE_PASSWORD to be PRESENT (even empty,
    # for local trust-auth). Windows cannot hold an empty env var — both cmd
    # `set VAR=` and .NET SetEnvironmentVariable('') DELETE it — so the empty
    # trust password would read as "missing" and the backend fail-fasts. Pass it
    # as a -D system property (which Spring's containsProperty() sees) so an
    # empty value still counts as present. This is the single java-launch point
    # for BOTH standalone and the .bat, so it fixes both.
    $argList += "-DDATABASE_PASSWORD=$($env:DATABASE_PASSWORD)"
    if ($PythonExe) { $argList += "-DPYTHON_EXE=$PythonExe" }
    if ($PythonDir) { $argList += "-DPYTHON_DIR=$PythonDir" }
    $argList += @('-jar', $JarPath)
    # Capture stderr so a boot failure (missing env, DB down, wrong Java major)
    # can be shown to the user instead of vanishing behind -WindowStyle Hidden.
    $Global:BackendLog = Join-Path $ProjectDir 'logs\backend-launcher.err.log'
    New-Item -ItemType Directory -Force -Path (Split-Path $Global:BackendLog) | Out-Null
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -WorkingDirectory $ProjectDir -WindowStyle Hidden -PassThru -RedirectStandardError $Global:BackendLog
    return $proc
}

function Start-Frontend {
    if (-not (Test-Path (Join-Path $FrontendDir 'package.json'))) {
        Write-Host "  [WARN] No se encontro frontend/package.json - el frontend no se inicio." -ForegroundColor Yellow
        return $null
    }
    # D4: pin the preview port and require it exactly (--strictPort) so a
    # port clash fails loudly instead of silently falling back to Vite's
    # default 4173 (the RED case in tasks 2.6).
    $argList = @('run', 'preview', '--', '--port', "$FrontendPort", '--strictPort')
    $proc = Start-Process -FilePath $NpmCmd -ArgumentList $argList -WorkingDirectory $FrontendDir -WindowStyle Hidden -PassThru
    return $proc
}

function Stop-TrackedProcess {
    param($Proc, [string]$Label)
    if ($null -eq $Proc) { return }
    try {
        $stillRunning = -not $Proc.HasExited
    } catch {
        $stillRunning = $false
    }
    if (-not $stillRunning) {
        Write-Host "  $Label ya estaba detenido." -ForegroundColor DarkGray
        return
    }
    try {
        & taskkill /PID $Proc.Id /T /F 2>$null | Out-Null
        Write-Host "  $Label detenido (PID $($Proc.Id))." -ForegroundColor DarkGray
    } catch {
        Write-Host "  [WARN] No se pudo detener $Label (PID $($Proc.Id)): $_" -ForegroundColor Yellow
    }
}

function Stop-Services {
    Stop-TrackedProcess -Proc $Global:FrontendProcess -Label 'Frontend'
    Stop-TrackedProcess -Proc $Global:BackendProcess -Label 'Backend'
}

# ----------------------------------------------------------------------------
# Menu actions - pure REST client, honest surfacing of no-op/conflict
# responses (spec "Honest surfacing of idempotent/no-op API responses").
# ----------------------------------------------------------------------------
function Show-Status {
    try {
        $status = Invoke-RestMethod -Uri "$ApiBase/api/status" -Method Get -TimeoutSec 5
        Write-Host ""
        Write-Host "  Estado     : $($status.status)"
        Write-Host "  Mensaje    : $($status.mensaje)"
        Write-Host "  Tiene data : $($status.tieneData)  Total: $($status.total)"
        return $status
    } catch {
        Write-Host "  [ERROR] No se pudo consultar /api/status: $_" -ForegroundColor Red
        return $null
    }
}

function Invoke-Scrape {
    $status = Show-Status
    if ($status -and $status.status -eq 'RUNNING') {
        Write-Host "  Ya hay un scraping en curso - no se ofrece lanzar otro." -ForegroundColor Yellow
        return
    }
    $precioMin = Read-Host "  precioMin (enter para default)"
    $precioMax = Read-Host "  precioMax (enter para default)"
    $sitiosRaw = Read-Host "  sitios separados por coma (enter para todos)"
    $forceRetrainRaw = Read-Host "  forceRetrain? (s/N)"

    $query = [System.Collections.Generic.List[string]]::new()
    if ($precioMin) { $query.Add("precioMin=$([uri]::EscapeDataString($precioMin))") }
    if ($precioMax) { $query.Add("precioMax=$([uri]::EscapeDataString($precioMax))") }
    if ($sitiosRaw) {
        foreach ($s in ($sitiosRaw -split ',')) {
            $trimmed = $s.Trim()
            if ($trimmed) { $query.Add("sitios=$([uri]::EscapeDataString($trimmed))") }
        }
    }
    if ($forceRetrainRaw -match '^(s|si|y|yes)$') { $query.Add('forceRetrain=true') }

    $qs = if ($query.Count -gt 0) { '?' + ($query -join '&') } else { '' }
    try {
        $resp = Invoke-RestMethod -Uri "$ApiBase/api/scrape$qs" -Method Post -TimeoutSec 10
        if ($resp.iniciado) {
            Write-Host "  Scraping iniciado: $($resp.mensaje)" -ForegroundColor Green
        } else {
            Write-Host "  No se inicio un nuevo scraping: $($resp.mensaje)" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  [ERROR] POST /api/scrape fallo: $_" -ForegroundColor Red
    }
}

function Invoke-Retrain {
    try {
        $resp = Invoke-WebRequest -Uri "$ApiBase/api/ml/entrenar" -Method Post -TimeoutSec 10 -UseBasicParsing
        Write-Host "  Entrenamiento iniciado (200)." -ForegroundColor Green
    } catch {
        $webResp = $_.Exception.Response
        if ($webResp -and ($webResp.StatusCode -eq 409 -or $webResp.StatusCode -eq 400)) {
            Write-Host "  Entrenamiento ya en curso - no se inicio otro." -ForegroundColor Yellow
        } else {
            Write-Host "  [ERROR] POST /api/ml/entrenar fallo: $_" -ForegroundColor Red
        }
    }
}

function Get-Sitios {
    try {
        $resp = Invoke-RestMethod -Uri "$ApiBase/api/sitios" -Method Get -TimeoutSec 5
        Write-Host ""
        Write-Host "  -- Sitios base --"
        foreach ($s in $resp.base) { Write-Host "  $($s.nombre)  $($s.url)" }
        Write-Host "  -- Sitios extra --"
        foreach ($s in $resp.extras) { Write-Host "  $($s.nombre)  $($s.url)  ($($s.plataforma))" }
    } catch {
        Write-Host "  [ERROR] GET /api/sitios fallo: $_" -ForegroundColor Red
    }
}

# Extracts the API's real `mensaje` field from a failed request's response
# body (PowerShell 5.1: Invoke-RestMethod throws on non-2xx and discards the
# body unless read back explicitly via $_.ErrorDetails.Message or the
# underlying response stream). Falls back to the raw exception text when the
# body isn't parseable JSON with a `mensaje` key.
function Get-ApiErrorMessage {
    param($ErrorRecord)
    $raw = $null
    if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
        $raw = $ErrorRecord.ErrorDetails.Message
    } elseif ($ErrorRecord.Exception.Response) {
        try {
            $stream = $ErrorRecord.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $raw = $reader.ReadToEnd()
        } catch {
            $raw = $null
        }
    }
    if ($raw) {
        try {
            $parsed = $raw | ConvertFrom-Json
            if ($parsed.mensaje) { return $parsed.mensaje }
        } catch {
            # not JSON / no mensaje field — fall through to raw text
        }
        return $raw
    }
    return $ErrorRecord.Exception.Message
}

function Add-Sitio {
    $nombre = Read-Host "  nombre"
    $url = Read-Host "  url"
    $plataforma = Read-Host "  plataforma (enter = tiendanube)"
    if (-not $plataforma) { $plataforma = 'tiendanube' }
    $json = Build-SiteJson -Nombre $nombre -Url $url -Plataforma $plataforma
    try {
        $resp = Invoke-RestMethod -Uri "$ApiBase/api/sitios" -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 10
        if ($resp.ok) {
            Write-Host "  $($resp.mensaje)" -ForegroundColor Green
        } else {
            Write-Host "  No se agrego: $($resp.mensaje)" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  No se agrego: $(Get-ApiErrorMessage $_)" -ForegroundColor Yellow
    }
}

function Remove-Sitio {
    $nombre = Read-Host "  nombre a eliminar"
    try {
        $resp = Invoke-RestMethod -Uri "$ApiBase/api/sitios/$([uri]::EscapeDataString($nombre))" -Method Delete -TimeoutSec 10
        if ($resp.ok) {
            Write-Host "  $($resp.mensaje)" -ForegroundColor Green
        } else {
            Write-Host "  $($resp.mensaje)" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  [ERROR] DELETE /api/sitios fallo: $_" -ForegroundColor Red
    }
}

function Open-Dashboard {
    if (-not $Global:FrontendReady) {
        Write-Host "  [WARN] El frontend todavia no respondio en $FrontendUrl." -ForegroundColor Yellow
    }
    try {
        Start-Process $FrontendUrl
    } catch {
        Write-Host "  [ERROR] No se pudo abrir el navegador: $_" -ForegroundColor Red
    }
}

function Invoke-SelfTest {
    Write-Host "  --selftest: validando contrato de API contra $ApiBase ..."
    $ok = $true
    try {
        $status = Invoke-RestMethod -Uri "$ApiBase/api/status" -Method Get -TimeoutSec 5
        foreach ($key in @('status', 'mensaje', 'tieneData')) {
            if (-not ($status.PSObject.Properties.Name -contains $key)) {
                Write-Host "  [DRIFT] /api/status no tiene el campo '$key'" -ForegroundColor Red
                $ok = $false
            }
        }
    } catch {
        Write-Host "  [DRIFT] GET /api/status fallo: $_" -ForegroundColor Red
        $ok = $false
    }
    try {
        $sitios = Invoke-RestMethod -Uri "$ApiBase/api/sitios" -Method Get -TimeoutSec 5
        foreach ($key in @('base', 'extras')) {
            if (-not ($sitios.PSObject.Properties.Name -contains $key)) {
                Write-Host "  [DRIFT] /api/sitios no tiene el campo '$key'" -ForegroundColor Red
                $ok = $false
            }
        }
    } catch {
        Write-Host "  [DRIFT] GET /api/sitios fallo: $_" -ForegroundColor Red
        $ok = $false
    }
    if ($ok) { Write-Host "  Contrato OK - sin drift detectado." -ForegroundColor Green }
    return $ok
}

# ----------------------------------------------------------------------------
# Main loop
# ----------------------------------------------------------------------------
function Show-Menu {
    param($status)
    Write-Host ""
    Write-Host "  ============================================================"
    Write-Host "   FASHION SCRAPER - MENU"
    Write-Host "  ============================================================"
    if (-not $Global:BackendReady) {
        Write-Host "  [!] Backend no disponible - acciones de API deshabilitadas." -ForegroundColor Yellow
    }
    $running = $status -and $status.status -eq 'RUNNING'
    Write-Host "  1) Ver estado"
    if ($running) {
        Write-Host "  2) Lanzar scraping        (deshabilitado - scraping en curso)" -ForegroundColor DarkGray
        Write-Host "  3) Reentrenar ML          (deshabilitado - scraping en curso)" -ForegroundColor DarkGray
    } else {
        Write-Host "  2) Lanzar scraping"
        Write-Host "  3) Reentrenar ML"
    }
    Write-Host "  4) Listar sitios"
    Write-Host "  5) Agregar sitio"
    Write-Host "  6) Eliminar sitio"
    Write-Host "  7) Abrir dashboard ($FrontendUrl)"
    Write-Host "  8) Selftest (validar contrato de API)"
    Write-Host "  Q) Salir"
    Write-Host ""
}

function Invoke-MainLoop {
    Write-Host "  Iniciando backend y frontend..."
    $Global:BackendProcess = Start-Backend
    $Global:FrontendProcess = Start-Frontend

    Write-Host "  Esperando a que el backend responda (hasta ~60s)..."
    $status = Wait-Backend
    if (-not $Global:BackendReady) {
        if ($Global:BackendProcess -and $Global:BackendProcess.HasExited) {
            Write-Host "  [ERROR] El backend se cerro al arrancar (exit code $($Global:BackendProcess.ExitCode))." -ForegroundColor Red
            if ($Global:BackendLog -and (Test-Path $Global:BackendLog)) {
                Write-Host "  Ultimas lineas del backend:" -ForegroundColor Yellow
                Get-Content -LiteralPath $Global:BackendLog -Tail 8 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
            }
            Write-Host "  Causa tipica: PostgreSQL no esta corriendo. Corre INSTALAR_Y_CORRER.bat (arranca la DB, setea el entorno y compila) o levanta Postgres antes de usar menu.ps1 standalone." -ForegroundColor Yellow
        } else {
            Write-Host "  [ERROR] El backend no respondio a tiempo. El menu se abre igual con acciones de API deshabilitadas." -ForegroundColor Red
        }
    }

    Write-Host "  Esperando a que el frontend responda (hasta ~60s)..."
    Wait-Frontend | Out-Null
    if (-not $Global:FrontendReady) {
        Write-Host "  [WARN] El frontend no respondio a tiempo en $FrontendUrl." -ForegroundColor Yellow
    }

    try {
        while ($true) {
            if ($Global:BackendReady) { $status = Show-Status }
            Show-Menu -status $status
            $choice = Read-Host "  Elegi una opcion"
            switch ($choice.ToUpper()) {
                '1' { $status = Show-Status }
                '2' {
                    if ($status -and $status.status -eq 'RUNNING') {
                        Write-Host "  Scraping en curso - accion deshabilitada." -ForegroundColor Yellow
                    } else { Invoke-Scrape }
                }
                '3' {
                    if ($status -and $status.status -eq 'RUNNING') {
                        Write-Host "  Scraping en curso - accion deshabilitada." -ForegroundColor Yellow
                    } else { Invoke-Retrain }
                }
                '4' { Get-Sitios }
                '5' { Add-Sitio }
                '6' { Remove-Sitio }
                '7' { Open-Dashboard }
                '8' { Invoke-SelfTest | Out-Null }
                'Q' { break }
                default { Write-Host "  Opcion invalida." -ForegroundColor Yellow }
            }
        }
    } finally {
        Write-Host ""
        Write-Host "  Cerrando servicios..."
        Stop-Services
    }
}

# ----------------------------------------------------------------------------
# D5 - Ctrl+C teardown. Runs synchronously on cancel; Cancel=$true keeps the
# process alive long enough for teardown to complete, then exits explicitly.
# ----------------------------------------------------------------------------
if (-not $env:MENU_TEST_MODE) {
    [Console]::add_CancelKeyPress({
        param($sender, $e)
        $e.Cancel = $true
        Write-Host ""
        Write-Host "  Ctrl+C detectado - cerrando servicios..."
        Stop-Services
        exit 0
    })
}

# ----------------------------------------------------------------------------
# Entry point - skipped entirely when dot-sourced for tests
# (tests/menu.Tests.ps1 sets MENU_TEST_MODE=1 before dot-sourcing).
# ----------------------------------------------------------------------------
if (-not $env:MENU_TEST_MODE) {
    if ($SelfTest) {
        $Global:BackendReady = $true
        $ok = Invoke-SelfTest
        if (-not $ok) { exit 1 }
        exit 0
    }
    Invoke-MainLoop
}
