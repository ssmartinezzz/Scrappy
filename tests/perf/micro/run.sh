#!/usr/bin/env bash
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/../../.." && pwd)"
JDK_COMPILA="${JAVA_HOME_COMPILA:-/home/santiago/openjdk-24_linux-x64_bin/jdk-24}"
JAVA_CORRE="${JAVA_RUN:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"
TOPE="systemd-run --user --scope -q -p MemoryMax=6G -p MemorySwapMax=0"
SALIDA="$AQUI/target/jmh-result.txt"

instalar_scraper() {
    local jar="$RAIZ/scraper/target/fashion-scraper-1.0.1.jar.original"
    JAVA_HOME="$JDK_COMPILA" $TOPE timeout 900 mvn -q -f "$RAIZ/scraper/pom.xml" clean package -DskipTests
    JAVA_HOME="$JDK_COMPILA" $TOPE timeout 300 mvn -q install:install-file \
        -Dfile="$jar" -DpomFile="$RAIZ/scraper/pom.xml" -Dclassifier=plain
}

compilar_micro() {
    JAVA_HOME="$JDK_COMPILA" $TOPE timeout 600 mvn -q -f "$AQUI/pom.xml" clean compile dependency:build-classpath \
        -Dmdep.outputFile="$AQUI/target/classpath.txt"
}

correr() {
    mkdir -p "$AQUI/target"
    $TOPE timeout 3600 "$JAVA_CORRE" -Xmx512m \
        -cp "$AQUI/target/classes:$(cat "$AQUI/target/classpath.txt")" \
        org.openjdk.jmh.Main "$@" -rff "$AQUI/target/jmh-result.json" -rf json | tee "$SALIDA"
}

case "${1:-}" in
    scraper) instalar_scraper ;;
    build)   compilar_micro ;;
    run)     shift; correr "$@" ;;
    all)     shift; instalar_scraper; compilar_micro; correr "$@" ;;
    *)       echo "uso: run.sh scraper | build | run [jmh args] | all [jmh args]" >&2; exit 2 ;;
esac
