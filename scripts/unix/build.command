#!/usr/bin/env bash
# ----------------------------------------------------------------------
#   Recipe Manager Pro - build script (macOS / Linux)
#   Compiles src/main/java recursively and produces RecipeManager.jar
# ----------------------------------------------------------------------
set -e

# プロジェクトルートに移動 (このスクリプトは scripts/unix/ にある)
cd "$(dirname "$0")/../.."

# JDK チェック
if ! command -v javac >/dev/null 2>&1; then
    echo "[ERROR] javac not found. Please install JDK 17 or later."
    echo "        https://adoptium.net/"
    read -n 1 -s -r -p "Press any key to exit..."
    exit 1
fi
if ! command -v jar >/dev/null 2>&1; then
    echo "[ERROR] jar command not found. Please install JDK 17 or later."
    read -n 1 -s -r -p "Press any key to exit..."
    exit 1
fi

# 既存の out/ をクリーンアップ
rm -rf out
mkdir out

LIBS="lib/sqlite-jdbc-3.45.3.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-nop-2.0.13.jar:lib/flatlaf-3.4.1.jar"

echo "[1/3] Compiling sources..."
# サブパッケージも含めて一括コンパイル
SOURCES=$(find src -name "*.java")
javac -encoding UTF-8 -cp "$LIBS" -d out -sourcepath src $SOURCES

echo "[2/3] Writing manifest..."
cat > manifest.tmp <<EOF
Manifest-Version: 1.0
Main-Class: main.java.SwingMain
Class-Path: lib/sqlite-jdbc-3.45.3.0.jar lib/slf4j-api-2.0.13.jar lib/slf4j-nop-2.0.13.jar lib/flatlaf-3.4.1.jar

EOF

echo "[3/3] Packaging RecipeManager.jar..."
jar cfm RecipeManager.jar manifest.tmp -C out .
rm -f manifest.tmp

echo
echo "============================================================"
echo "  [OK] Build done. RecipeManager.jar is ready."
echo "  - Run with: ./scripts/unix/run.command"
echo "  - Or: java -jar RecipeManager.jar"
echo "============================================================"
echo
read -n 1 -s -r -p "Press any key to close..."
