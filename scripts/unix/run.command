#!/usr/bin/env bash
# ----------------------------------------------------------------------
#   Recipe Manager Pro - launcher (macOS / Linux)
#   - Auto-builds the JAR on first run
#   - Launches the GUI in the background
# ----------------------------------------------------------------------
set -e

# プロジェクトルートに移動 (このスクリプトは scripts/unix/ にある)
cd "$(dirname "$0")/../.."

# 初回実行時にビルド
if [ ! -f "RecipeManager.jar" ]; then
    echo "First-run setup: building..."
    bash "$(dirname "$0")/../unix/build.command"
fi

# JRE/JDK チェック
if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] java not found. Please install JRE/JDK 17 or later."
    read -n 1 -s -r -p "Press any key to exit..."
    exit 1
fi

# GUI をバックグラウンド起動 (端末を閉じても継続)
# nohup でハングアップ無視、stdout/stderr は破棄
nohup java -jar RecipeManager.jar >/dev/null 2>&1 &
disown 2>/dev/null || true
