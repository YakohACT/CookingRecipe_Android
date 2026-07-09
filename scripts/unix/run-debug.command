#!/usr/bin/env bash
# ----------------------------------------------------------------------
#   Recipe Manager Pro - debug launcher (with console, macOS / Linux)
#   Use this when you need stdout/stderr for troubleshooting.
# ----------------------------------------------------------------------
set -e

# プロジェクトルートに移動 (このスクリプトは scripts/unix/ にある)
cd "$(dirname "$0")/../.."

if [ ! -f "RecipeManager.jar" ]; then
    echo "RecipeManager.jar not found. Running build first..."
    bash "$(dirname "$0")/../unix/build.command"
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] java not found. Please install JRE/JDK 17 or later."
    read -n 1 -s -r -p "Press any key to exit..."
    exit 1
fi

echo "=== Launching with console attached. Close the GUI to exit. ==="
java -jar RecipeManager.jar

echo
echo "=== Application exited. ==="
read -n 1 -s -r -p "Press any key to close..."
