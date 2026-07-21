# Fashion Scraper Argentina — backend image (docker-install-alternative)
#
# Additive alternative to INSTALAR_Y_CORRER.bat / Ejecutar_instalar.sh. This
# file does not modify any existing installer, launcher, or source file.
#
# Build stage: compile the fat JAR with Maven + JDK 21.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY scraper/pom.xml scraper/pom.xml
COPY scraper/src scraper/src

RUN mvn -f scraper/pom.xml -B package -DskipTests

# Runtime stage: Playwright's own image bundles Chromium + matching OS libs
# for Playwright 1.44.0 (must stay in lockstep with pom.xml playwright.version).
# NOTE: this base image ships JDK 17 — Temurin 21 is installed explicitly
# below because the app is compiled for Java 21 (java.version=21 in pom.xml).
FROM mcr.microsoft.com/playwright/java:v1.44.0-jammy

# --- Temurin 21 JDK (Adoptium apt repo) ------------------------------------
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget apt-transport-https gnupg ca-certificates \
    && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-21-jdk \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# --- Python 3.11 + pip + pinned ML deps (matches installer versions) ------
# Contract: `python3` must resolve on PATH (PythonRunner.detectarPython()
# step-3 PATH probe). No -DPYTHON_EXE override is used.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3.11 python3-pip python3.11-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --no-cache-dir --upgrade pip \
    && python3 -m pip install --no-cache-dir psycopg2-binary \
    && python3 -m pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu \
    && python3 -m pip install --no-cache-dir \
        open_clip_torch==2.24.0 \
        huggingface_hub==0.24.6 \
        transformers==4.44.2

WORKDIR /app
COPY --from=build /build/scraper/target/fashion-scraper-1.0.0.jar /app/fashion-scraper-1.0.0.jar

# Model cache / HF_HOME live on a named volume (see docker-compose.yml) so
# downloaded weights survive `docker compose down` + `up`.
ENV SCRAPER_MODELS_ROOT=/models
ENV HF_HOME=/models/marqo
ENV LOG_DIR=/app/logs

EXPOSE 3000

ENTRYPOINT ["java", "-jar", "/app/fashion-scraper-1.0.0.jar"]
