@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

if "%~1"=="__run__" goto :main
cmd /k "%~f0" __run__
exit /b

:main
title Fashion Scraper Argentina
cls
echo.
echo  ============================================================
echo   FASHION SCRAPER ARGENTINA
echo  ============================================================
echo.

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "TOOLS=%ROOT%\_tools"
set "JAVA_DIR=%TOOLS%\jdk21"
set "JAVA_EXE=%JAVA_DIR%\bin\java.exe"
set "MVN_DIR=%TOOLS%\maven"
set "MVN_EXE=%MVN_DIR%\bin\mvn.cmd"
set "ALLURE_DIR=%TOOLS%\allure"
set "ALLURE_EXE=%ALLURE_DIR%\bin\allure.bat"
set "PYTHON_DIR=%TOOLS%\python"
set "PYTHON_EXE=%PYTHON_DIR%\python.exe"
set "PIP_EXE=%PYTHON_DIR%\Scripts\pip.exe"
set "NODE_DIR=%TOOLS%\node"
set "PROJECT=%ROOT%\scraper"
set "JAR=%PROJECT%\scraper.jar"
:: decouple-services-postgres, Batch 4 (task 4.2): portable PostgreSQL —
:: mirrors the jdk21/maven pattern (binaries under _tools/, datadir + running
:: server are the only local state). ENV_FILE is the gitignored .env the
:: launcher generates/sources so Java/Python/frontend share ONE config source
:: (spec "Environment-Only Configuration" / "Installer-generated .env sourced
:: by launcher").
set "PG_DIR=%TOOLS%\pgsql"
set "PG_BIN=%PG_DIR%\bin"
set "PG_DATA=%TOOLS%\pgdata"
set "PG_PORT=5432"
set "PG_DB=scraper"
set "PG_USER=postgres"
set "ENV_FILE=%ROOT%\.env"
if not exist "%TOOLS%" mkdir "%TOOLS%"

echo  Raiz    : %ROOT%
echo  Proyecto: %PROJECT%
echo.

:: ============================================================
:: [1/8] Internet
:: ============================================================
echo [1/8] Verificando internet...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "try{(New-Object Net.WebClient).DownloadString('https://www.google.com')|Out-Null;exit 0}catch{exit 1}"
if errorlevel 1 (
    echo  [ERROR] Sin internet.
    pause & exit /b 1
)
echo        OK
echo.

:: ============================================================
:: [2/8] Java 21
:: ============================================================
echo [2/8] Java 21...
if exist "%JAVA_EXE%" (
    echo        Ya instalado.
    goto :java_ok
)
echo        Descargando OpenJDK 21 aprox 190MB...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3+9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip'," ^
  "'%TOOLS%\jdk.zip')"
if not exist "%TOOLS%\jdk.zip" (
    echo  [ERROR] Descarga Java fallo.
    pause & exit /b 1
)
echo        Descomprimiendo...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\jdk.zip' -DestinationPath '%TOOLS%\jdk_tmp' -Force"
for /d %%D in ("%TOOLS%\jdk_tmp\*") do move "%%D" "%JAVA_DIR%" >nul 2>&1
rmdir /s /q "%TOOLS%\jdk_tmp" 2>nul
del /f /q "%TOOLS%\jdk.zip" 2>nul
if not exist "%JAVA_EXE%" (
    echo  [ERROR] Extraccion Java fallo.
    pause & exit /b 1
)
echo        Java 21 listo.
:java_ok
set "JAVA_HOME=%JAVA_DIR%"
set "PATH=%JAVA_DIR%\bin;%PATH%"
echo.

:: ============================================================
:: [3/8] PostgreSQL portable (decouple-services-postgres, Batch 4)
:: ============================================================
echo [3/8] PostgreSQL portable...
if exist "%PG_BIN%\postgres.exe" (
    echo        Ya instalado.
    goto :pg_bin_ok
)
echo        Descargando PostgreSQL 16 portable aprox 320MB...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://get.enterprisedb.com/postgresql/postgresql-16.4-1-windows-x64-binaries.zip'," ^
  "'%TOOLS%\pgsql.zip')"
if not exist "%TOOLS%\pgsql.zip" (
    echo  [ERROR] Descarga PostgreSQL fallo.
    pause & exit /b 1
)
echo        Descomprimiendo PostgreSQL...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\pgsql.zip' -DestinationPath '%TOOLS%\pgsql_tmp' -Force"
move "%TOOLS%\pgsql_tmp\pgsql" "%PG_DIR%" >nul 2>&1
:: pgAdmin4/StackBuilder/doc no se usan en runtime headless — se descartan.
rmdir /s /q "%TOOLS%\pgsql_tmp" 2>nul
del /f /q "%TOOLS%\pgsql.zip" 2>nul
if not exist "%PG_BIN%\postgres.exe" (
    echo  [ERROR] Extraccion PostgreSQL fallo.
    pause & exit /b 1
)
echo        PostgreSQL portable listo.
:pg_bin_ok

if exist "%PG_DATA%\PG_VERSION" (
    echo        Data directory ya inicializado.
    goto :pg_initdb_ok
)
echo        Inicializando data directory...
"%PG_BIN%\initdb.exe" -D "%PG_DATA%" -U "%PG_USER%" -A trust --locale=C -E UTF8 >nul
if not exist "%PG_DATA%\PG_VERSION" (
    echo  [ERROR] initdb fallo.
    pause & exit /b 1
)
:pg_initdb_ok

"%PG_BIN%\pg_ctl.exe" -D "%PG_DATA%" status >nul 2>&1
if not errorlevel 1 (
    echo        Servidor ya esta corriendo.
    goto :pg_start_ok
)
echo        Iniciando servidor en 127.0.0.1:%PG_PORT%...
"%PG_BIN%\pg_ctl.exe" -D "%PG_DATA%" ^
  -o "-p %PG_PORT% -c listen_addresses=127.0.0.1 -c unix_socket_directories=" ^
  -l "%TOOLS%\pgserver.log" -w start
if errorlevel 1 (
    echo  [ERROR] PostgreSQL no arranco. Ver %TOOLS%\pgserver.log.
    pause & exit /b 1
)
:pg_start_ok

"%PG_BIN%\psql.exe" -h 127.0.0.1 -p %PG_PORT% -U "%PG_USER%" -tAc ^
  "SELECT 1 FROM pg_database WHERE datname='%PG_DB%'" > "%TOOLS%\pgcheck.tmp" 2>nul
set "PG_DB_EXISTS="
set /p PG_DB_EXISTS=<"%TOOLS%\pgcheck.tmp"
del /f /q "%TOOLS%\pgcheck.tmp" 2>nul
if not "!PG_DB_EXISTS!"=="1" (
    echo        Creando base de datos '%PG_DB%'...
    "%PG_BIN%\createdb.exe" -h 127.0.0.1 -p %PG_PORT% -U "%PG_USER%" "%PG_DB%"
)
echo        PostgreSQL listo en 127.0.0.1:%PG_PORT%/%PG_DB%.
echo.

:: ============================================================
:: [4/8] Python 3.11 + pip + paquetes ML
:: ============================================================
echo [4/8] Python + scikit-learn + PyTorch...
echo.

:: 3a - Python embeddable
if exist "%PYTHON_EXE%" (
    echo        Python ya instalado.
    goto :python_exists
)
echo        Descargando Python 3.11 aprox 15MB...
mkdir "%PYTHON_DIR%" 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip'," ^
  "'%TOOLS%\python.zip')"
if not exist "%TOOLS%\python.zip" (
    echo        AVISO: No se pudo descargar Python. ML desactivado.
    goto :python_ok
)
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\python.zip' -DestinationPath '%PYTHON_DIR%' -Force"
del /f /q "%TOOLS%\python.zip" 2>nul
echo        Habilitando site-packages...
for %%F in ("%PYTHON_DIR%\python3*._pth") do (
    powershell -NoProfile -Command ^
      "(Get-Content '%%F') -replace '#import site','import site' | Set-Content '%%F'"
)
echo        Python 3.11 extraido.

:python_exists
set "PATH=%PYTHON_DIR%;%PYTHON_DIR%\Scripts;%PATH%"
if not exist "%PYTHON_EXE%" (
    echo        AVISO: Python no disponible. ML desactivado.
    goto :python_ok
)

:: 3b - pip
if exist "%PIP_EXE%" (
    echo        pip ya instalado.
    goto :pip_ok
)
echo        Instalando pip...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://bootstrap.pypa.io/get-pip.py','%PYTHON_DIR%\get-pip.py')"
if exist "%PYTHON_DIR%\get-pip.py" (
    "%PYTHON_EXE%" "%PYTHON_DIR%\get-pip.py" --no-warn-script-location --quiet
    del /f /q "%PYTHON_DIR%\get-pip.py" 2>nul
)
:pip_ok
if not exist "%PIP_EXE%" (
    echo        AVISO: pip no disponible.
    goto :python_ok
)

:: 3c - Detectar GPU NVIDIA sin usar pipes
:: Usamos nvidia-smi directo a archivo .tmp
echo        Detectando GPU NVIDIA...
set "HAS_GPU=0"
set "CUDA_MAJOR=12"
set "TORCH_INDEX=https://download.pytorch.org/whl/cpu"
set "GPU_NAME=Sin GPU"
set "TMP_NAME=%TOOLS%\gpuname.tmp"
set "TMP_FULL=%TOOLS%\gpufull.tmp"

nvidia-smi --query-gpu=name --format=csv,noheader > "%TMP_NAME%" 2>nul
if errorlevel 1 (
    echo        Sin GPU NVIDIA - PyTorch modo CPU
    del /f /q "%TMP_NAME%" 2>nul
    goto :torch_cpu
)

for /f "usebackq tokens=*" %%G in ("%TMP_NAME%") do set "GPU_NAME=%%G"
del /f /q "%TMP_NAME%" 2>nul

nvidia-smi > "%TMP_FULL%" 2>nul
:: Parsear CUDA version buscando el patron "CUDA Version: XX" en el archivo
set "CUDA_STR="
for /f "tokens=*" %%L in ('findstr /C:"CUDA Version" "%TMP_FULL%" 2^>nul') do set "CUDA_LINE=%%L"
del /f /q "%TMP_FULL%" 2>nul

:: Extraer numero CUDA usando nvidia-smi --version-spec (mas confiable)
:: nvidia-smi --query-gpu=driver_version --format=csv,noheader solo da driver, no CUDA
:: En su lugar parseamos la linea buscando token que empiece con 1 o 2 digitos seguido de punto
if defined CUDA_LINE (
    for %%A in (!CUDA_LINE!) do (
        set "TOK=%%A"
        if "!TOK:~1,1!"=="." set "CUDA_MAJOR=!TOK:~0,1!"
        if "!TOK:~2,1!"=="." set "CUDA_MAJOR=!TOK:~0,2!"
    )
)

:: Si no se pudo parsear pero hay GPU NVIDIA moderna -> asumir CUDA 12
if "!CUDA_MAJOR!"=="12" (
    echo        CUDA !CUDA_MAJOR! detectado.
) else if "!CUDA_MAJOR!"=="11" (
    echo        CUDA !CUDA_MAJOR! detectado.
) else (
    echo        CUDA version no parseada - asumiendo CUDA 12 para GPU NVIDIA...
    set "CUDA_MAJOR=12"
)

set "HAS_GPU=1"
if "!CUDA_MAJOR!"=="12" set "TORCH_INDEX=https://download.pytorch.org/whl/cu121"
if "!CUDA_MAJOR!"=="11" set "TORCH_INDEX=https://download.pytorch.org/whl/cu118"
echo        GPU: !GPU_NAME! - CUDA !CUDA_MAJOR!
goto :torch_install

:torch_cpu
echo        Modo CPU seleccionado.

:torch_install
:: 3d - scikit-learn
"%PYTHON_EXE%" -c "import sklearn" 2>nul
if errorlevel 1 (
    echo        Instalando scikit-learn, numpy, Pillow aprox 100MB...
    "%PIP_EXE%" install --quiet --no-warn-script-location --timeout 120 --retries 5 numpy scipy scikit-learn Pillow
    echo        scikit-learn instalado.
) else (
    echo        scikit-learn ya instalado.
)

:: decouple-services-postgres, Batch 4 (task 4.2): psycopg2-binary — el
:: pipeline ML (ml_pipeline.py/ml_train.py/ml_embeddings.py) habla Postgres
:: directo via DATABASE_URL (design D4), ya no SQLite.
"%PYTHON_EXE%" -c "import psycopg2" 2>nul
if errorlevel 1 (
    echo        Instalando psycopg2-binary aprox 3MB...
    "%PIP_EXE%" install --quiet --no-warn-script-location --timeout 120 --retries 5 psycopg2-binary
    echo        psycopg2-binary instalado.
) else (
    echo        psycopg2-binary ya instalado.
)

:: 3e - PyTorch
"%PYTHON_EXE%" -c "import torch" 2>nul
if errorlevel 1 (
    if "!HAS_GPU!"=="1" (
        echo        Instalando PyTorch con CUDA !CUDA_MAJOR! para !GPU_NAME!...
        echo        Descargando aprox 2.5GB - puede tardar varios minutos...
        echo        (Si se corta la conexion pip retoma automaticamente)
    ) else (
        echo        Instalando PyTorch CPU aprox 250MB...
    )
    "%PIP_EXE%" install --no-warn-script-location ^
        --timeout 300 --retries 5 ^
        torch torchvision --index-url "!TORCH_INDEX!"
    echo        PyTorch instalado.
) else (
    echo        PyTorch ya instalado.
)

:: 3f - open_clip_torch + huggingface_hub + transformers (clasificacion de imagenes por IA)
:: Pines defensivos: evitan que la resolucion de dependencias de pip toque
:: el torch/torchvision ya instalado en 3e (CPU o CUDA). Verificamos ademas
:: que la version de torch no haya cambiado tras instalar estos paquetes.
:: transformers es requerido por el tokenizer de Marqo-FashionSigLIP
:: (open_clip.get_tokenizer para modelos hf-hub); sin el, ml_embeddings
:: carga el modelo pero no puede computar prompt embeddings y el backfill
:: degrada a "modelo no disponible" con 0 filas clasificadas.
set "IMGCLS_DEPS_OK=0"
"%PYTHON_EXE%" -c "import open_clip, huggingface_hub, transformers" 2>nul
if errorlevel 1 (
    echo        Instalando open_clip_torch + huggingface_hub + transformers aprox 60MB...
    set "TORCH_VER_BEFORE="
    "%PYTHON_EXE%" -c "import torch; print(torch.__version__)" > "%TOOLS%\torchver_before.tmp" 2>nul
    for /f "usebackq tokens=*" %%V in ("%TOOLS%\torchver_before.tmp") do set "TORCH_VER_BEFORE=%%V"
    del /f /q "%TOOLS%\torchver_before.tmp" 2>nul
    "%PIP_EXE%" install --quiet --no-warn-script-location --timeout 300 --retries 5 open_clip_torch==2.24.0 huggingface_hub==0.24.6 transformers==4.44.2
    set "TORCH_VER_AFTER="
    "%PYTHON_EXE%" -c "import torch; print(torch.__version__)" > "%TOOLS%\torchver_after.tmp" 2>nul
    for /f "usebackq tokens=*" %%V in ("%TOOLS%\torchver_after.tmp") do set "TORCH_VER_AFTER=%%V"
    del /f /q "%TOOLS%\torchver_after.tmp" 2>nul
    if not "!TORCH_VER_BEFORE!"=="!TORCH_VER_AFTER!" (
        echo        AVISO: PyTorch cambio de version tras instalar open_clip_torch ^(!TORCH_VER_BEFORE! -^> !TORCH_VER_AFTER!^). Verificar compatibilidad CUDA/CPU.
    ) else (
        echo        open_clip_torch + huggingface_hub + transformers instalados. PyTorch sin cambios ^(!TORCH_VER_AFTER!^).
    )
) else (
    echo        open_clip_torch ya instalado.
)
"%PYTHON_EXE%" -c "import open_clip, huggingface_hub, transformers" 2>nul
if not errorlevel 1 set "IMGCLS_DEPS_OK=1"

:: 3g - Pesos del modelo Marqo-FashionSigLIP aprox 300MB
:: decouple-services-postgres (Batch 2/4, design D5): el runtime ya no deriva
:: HF_HOME de un archivo scraper.db — PythonRunner.hfHomeParaModelsRoot y
:: ml_embeddings._default_hf_home lo calculan como <SCRAPER_MODELS_ROOT>/marqo,
:: con SCRAPER_MODELS_ROOT fijado a %PROJECT%\_models mas abajo (bloque .env).
:: El warm-up del installer debe apuntar al mismo directorio o el backfill
:: arranca con cache frio y degrada a "modelo no disponible".
set "MODELS_DIR=%PROJECT%\_models"
set "MARQO_DIR=%MODELS_DIR%\marqo"
set "HF_HOME=%MARQO_DIR%"
:: Migracion: instalaciones previas descargaron los pesos en %ROOT%\_models\marqo
if exist "%ROOT%\_models\marqo\.ready" if not exist "%MARQO_DIR%\.ready" (
    if exist "%MARQO_DIR%" rmdir "%MARQO_DIR%" 2>nul
    if not exist "%MARQO_DIR%" (
        echo        Moviendo cache del modelo a %MARQO_DIR%...
        move "%ROOT%\_models\marqo" "%MARQO_DIR%" >nul
    )
)
if not exist "%MARQO_DIR%" mkdir "%MARQO_DIR%" 2>nul
if "!IMGCLS_DEPS_OK!"=="0" (
    echo        AVISO: open_clip_torch no disponible. Clasificacion por imagen desactivada.
    goto :marqo_ok
)
if exist "%MARQO_DIR%\.ready" (
    echo        Modelo Marqo-FashionSigLIP ya descargado.
    goto :marqo_ok
)
echo        Descargando modelo Marqo-FashionSigLIP aprox 300MB...
echo        (Si se corta la conexion, reintenta automaticamente hasta 5 veces)
set "HF_HUB_DOWNLOAD_TIMEOUT=300"
set "MARQO_OK=0"
for /l %%R in (1,1,5) do (
    if "!MARQO_OK!"=="0" (
        "%PYTHON_EXE%" -c "from huggingface_hub import snapshot_download; snapshot_download('Marqo/marqo-fashionSigLIP')" >nul 2>&1
        if not errorlevel 1 set "MARQO_OK=1"
    )
)
if "!MARQO_OK!"=="1" (
    echo. > "%MARQO_DIR%\.ready"
    echo        Modelo Marqo-FashionSigLIP listo.
) else (
    echo        AVISO: No se pudo descargar el modelo tras 5 intentos. Clasificacion por imagen desactivada esta corrida.
)
:marqo_ok

:python_ok
echo.

:: ============================================================
:: [5/8] Node.js + Frontend
:: ============================================================
echo [5/8] Node.js + Frontend React/Vite...
if exist "%NODE_DIR%\node.exe" (
    echo        Node.js ya instalado.
    goto :node_ok
)
echo        Descargando Node.js 20 aprox 30MB...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://nodejs.org/dist/v20.11.0/node-v20.11.0-win-x64.zip'," ^
  "'%TOOLS%\node.zip')"
if not exist "%TOOLS%\node.zip" (
    echo  [ERROR] Descarga Node.js fallo.
    pause & exit /b 1
)
echo        Descomprimiendo Node.js...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\node.zip' -DestinationPath '%TOOLS%\noderaw' -Force"
for /d %%i in ("%TOOLS%\noderaw\node-*") do move "%%i" "%NODE_DIR%" >nul 2>&1
rmdir /s /q "%TOOLS%\noderaw" 2>nul
del /f /q "%TOOLS%\node.zip" 2>nul
echo        Node.js listo.
:node_ok
set "PATH=%NODE_DIR%;%PATH%"

echo        Compilando frontend...
set "FRONTEND_DIR=%ROOT%\frontend"
if not exist "%FRONTEND_DIR%\package.json" (
    echo  [ERROR] Falta frontend\package.json
    pause & exit /b 1
)
cd /d "%FRONTEND_DIR%"
set "NEED_INSTALL=0"
if not exist "node_modules" set "NEED_INSTALL=1"
if not exist "node_modules\.install-stamp" set "NEED_INSTALL=1"
if "%NEED_INSTALL%"=="0" (
    for /f %%i in ('powershell -NoProfile -Command "if ((Get-Item 'package.json').LastWriteTime -gt (Get-Item 'node_modules\.install-stamp').LastWriteTime) {1} else {0}"') do set "NEED_INSTALL=%%i"
)
if "%NEED_INSTALL%"=="1" (
    echo        Instalando dependencias npm...
    call "%NODE_DIR%\npm.cmd" install --prefer-offline 2>nul
    if errorlevel 1 (
        set "NPMCLI=%NODE_DIR%\node_modules\npm\bin\npm-cli.js"
        if exist "!NPMCLI!" (
            "%NODE_DIR%\node.exe" "!NPMCLI!" install --prefer-offline
        ) else (
            npm install --prefer-offline
        )
        if errorlevel 1 (
            echo  [ERROR] npm install fallo.
            pause & exit /b 1
        )
    )
    echo. > "node_modules\.install-stamp"
)
call "%NODE_DIR%\npm.cmd" run build 2>nul
if errorlevel 1 (
    set "NPMCLI=%NODE_DIR%\node_modules\npm\bin\npm-cli.js"
    if exist "!NPMCLI!" (
        "%NODE_DIR%\node.exe" "!NPMCLI!" run build
    ) else (
        npm run build
    )
    if errorlevel 1 (
        echo  [ERROR] Build frontend fallo.
        pause & exit /b 1
    )
)
echo        Frontend compilado OK.
cd /d "%ROOT%"
echo.

:: ============================================================
:: [6/8] Maven
:: ============================================================
echo [6/8] Maven...
if exist "%MVN_EXE%" (
    echo        Ya instalado.
    goto :mvn_ok
)
echo        Descargando Maven aprox 9MB...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "try { (New-Object Net.WebClient).DownloadFile(" ^
  "'https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip'," ^
  "'%TOOLS%\mvn.zip') } catch {" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip'," ^
  "'%TOOLS%\mvn.zip') }"
if not exist "%TOOLS%\mvn.zip" (
    echo  [ERROR] Descarga Maven fallo.
    pause & exit /b 1
)
echo        Descomprimiendo Maven...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\mvn.zip' -DestinationPath '%TOOLS%\mvn_tmp' -Force"
for /d %%D in ("%TOOLS%\mvn_tmp\*") do move "%%D" "%MVN_DIR%" >nul 2>&1
rmdir /s /q "%TOOLS%\mvn_tmp" 2>nul
del /f /q "%TOOLS%\mvn.zip" 2>nul
if not exist "%MVN_EXE%" (
    echo  [ERROR] Maven no extraido.
    pause & exit /b 1
)
echo        Maven listo.
:mvn_ok
set "PATH=%MVN_DIR%\bin;%PATH%"
echo.

:: ============================================================
:: [7/8] Allure CLI (reportes de test)
:: ============================================================
echo [7/8] Allure CLI...
if exist "%ALLURE_EXE%" (
    echo        Ya instalado.
    goto :allure_ok
)
echo        Descargando Allure 2.29.0 aprox 25MB...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$ProgressPreference='SilentlyContinue';" ^
  "try { (New-Object Net.WebClient).DownloadFile(" ^
  "'https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/2.29.0/allure-commandline-2.29.0.zip'," ^
  "'%TOOLS%\allure.zip') } catch {" ^
  "(New-Object Net.WebClient).DownloadFile(" ^
  "'https://github.com/allure-framework/allure2/releases/download/2.29.0/allure-2.29.0.zip'," ^
  "'%TOOLS%\allure.zip') }"
if not exist "%TOOLS%\allure.zip" (
    echo        AVISO: No se pudo descargar Allure. Reportes HTML de test desactivados.
    goto :allure_ok
)
echo        Descomprimiendo Allure...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%TOOLS%\allure.zip' -DestinationPath '%TOOLS%\allure_tmp' -Force"
for /d %%D in ("%TOOLS%\allure_tmp\*") do move "%%D" "%ALLURE_DIR%" >nul 2>&1
rmdir /s /q "%TOOLS%\allure_tmp" 2>nul
del /f /q "%TOOLS%\allure.zip" 2>nul
if not exist "%ALLURE_EXE%" (
    echo        AVISO: Extraccion Allure fallo. Reportes HTML de test desactivados.
    goto :allure_ok
)
echo        Allure CLI listo.
:allure_ok
if exist "%ALLURE_EXE%" set "PATH=%ALLURE_DIR%\bin;%PATH%"
echo.

:: ============================================================
:: [8/8] Compilar JAR
:: ============================================================
echo [8/8] Compilando backend Java...
if exist "%JAR%" (
    echo        JAR ya existe, saltando compilacion.
    goto :jar_ok
)
echo        Primera compilacion con Maven aprox 3 minutos...
echo        (las proximas veces saltea esto automaticamente)
echo.
pushd "%PROJECT%"
call "%MVN_EXE%" clean package -DskipTests --batch-mode ^
  -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
set "MVN_EXIT=!errorlevel!"
popd
if "!MVN_EXIT!" NEQ "0" (
    echo  [ERROR] Compilacion Java fallo. Ver errores arriba.
    pause & exit /b 1
)
if not exist "%PROJECT%\target\fashion-scraper-1.0.0.jar" (
    echo  [ERROR] JAR no encontrado.
    pause & exit /b 1
)
copy /y "%PROJECT%\target\fashion-scraper-1.0.0.jar" "%JAR%" >nul
echo        Backend listo.
:jar_ok
echo.

:: ============================================================
:: Generar .env (gitignored) — env-only config, sin defaults silenciosos
:: (spec "Environment-Only Configuration" / "Installer-generated .env sourced
:: by launcher"). DATABASE_URL usa el formato jdbc: que Spring/HikariCP
:: requiere; PythonRunner.toPsycopgDsn lo traduce al formato libpq/psycopg2
:: antes de pasarlo al subproceso Python (design D5, Batch 4 fix).
:: ============================================================
set "SCRAPER_MODELS_ROOT=%PROJECT%\_models"
set "DATABASE_URL=jdbc:postgresql://127.0.0.1:%PG_PORT%/%PG_DB%"
set "DATABASE_USERNAME=%PG_USER%"
set "DATABASE_PASSWORD="
set "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173"
set "VITE_API_BASE_URL=http://localhost:3000"
set "APP_OPEN_URL=http://localhost:3000"
(
  echo DATABASE_URL=%DATABASE_URL%
  echo DATABASE_USERNAME=%DATABASE_USERNAME%
  echo DATABASE_PASSWORD=%DATABASE_PASSWORD%
  echo SCRAPER_MODELS_ROOT=%SCRAPER_MODELS_ROOT%
  echo APP_CORS_ALLOWED_ORIGINS=%APP_CORS_ALLOWED_ORIGINS%
  echo VITE_API_BASE_URL=%VITE_API_BASE_URL%
  echo APP_OPEN_URL=%APP_OPEN_URL%
) > "%ENV_FILE%"
echo        .env generado en %ENV_FILE% ^(gitignored^)
echo.

:: ============================================================
:: LANZAR servidor
:: ============================================================
cls
echo.
echo  ============================================================
echo   FASHION SCRAPER - SERVIDOR LISTO
echo  ============================================================
echo.
echo   API      : http://localhost:3000  ^(API-only — backend ya no sirve la SPA^)
echo   DB       : PostgreSQL 127.0.0.1:%PG_PORT%/%PG_DB%
if "!HAS_GPU!"=="1" (
    echo   GPU      : !GPU_NAME! - CUDA !CUDA_MAJOR!
    echo   Training : EfficientNet-B3 habilitado
) else (
    echo   GPU      : Sin GPU NVIDIA - entrenamiento en CPU
)
if exist "%ALLURE_EXE%" (
    echo   Tests    : para correr las suites -^> cd scraper ^&^& mvn test
    echo   Allure   : ver reporte HTML -^> allure serve scraper\target\allure-results  ^(despues de 'mvn test'^)
)
echo   Detener  : Ctrl+C
echo  ============================================================
echo.

:: -- Limpieza de logs sueltos (idempotente, NO toca la carpeta logs\) --
:: Raiz del proyecto (%ROOT%, sin backslash final; normalizado en linea 19)
del /f /q "%ROOT%\app.log"           2>nul
del /f /q "%ROOT%\server_run.log"    2>nul
del /f /q "%ROOT%\scraper.log"       2>nul
del /f /q "%ROOT%\scraper.*.log"     2>nul
:: Directorio del jar / CWD en runtime (%PROJECT% = %ROOT%\scraper)
del /f /q "%PROJECT%\app.log"        2>nul
del /f /q "%PROJECT%\server_run.log" 2>nul
del /f /q "%PROJECT%\scraper.log"    2>nul
del /f /q "%PROJECT%\scraper.*.log"  2>nul
mkdir "%PROJECT%\logs" 2>nul

pushd "%PROJECT%"
"%JAVA_EXE%" ^
  -Xmx768m ^
  -Dfile.encoding=UTF-8 ^
  -DPYTHON_EXE="%PYTHON_EXE%" ^
  -DPYTHON_DIR="%PYTHON_DIR%" ^
  -jar "%JAR%"
popd

echo.
echo  Servidor detenido. Presiona cualquier tecla para cerrar...
pause >nul
