#!/usr/bin/env bash
# ----------------------------------------------------------------------
#   Recipe Manager Pro - Whisper transcription auto-setup (macOS / Linux)
#
#   Downloads everything needed to transcribe YouTube videos locally
#   when the original captions are missing:
#     - yt-dlp        (audio downloader)
#     - ffmpeg        (audio re-encoding to 16kHz mono WAV)
#     - whisper.cpp   (speech-to-text; binary name "whisper-cli")
#     - ggml whisper model (base, ~140MB - balanced speed/accuracy)
#
#   Strategy:
#     - macOS  : prefer Homebrew (brew install yt-dlp ffmpeg whisper-cpp)
#     - Linux  : prefer system package manager; if not available print
#                manual install instructions for each tool
#     - Model  : always downloaded into lib/models/ via curl
#
#   Re-run safe: anything already on PATH or already in lib/ is skipped.
# ----------------------------------------------------------------------
set -e

# プロジェクトルートに移動 (このスクリプトは scripts/unix/ にある)
cd "$(dirname "$0")/../.."

mkdir -p lib/tools lib/models

OS="$(uname -s)"
ANY_MISSING=0

# --- ヘルパー -----------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

require_curl() {
    if ! have curl; then
        echo "[ERROR] curl が必要です。事前にインストールしてください。"
        exit 1
    fi
}

# Homebrew が使える環境(主に macOS)で `brew install` を試みる
brew_install() {
    local pkg="$1"
    if have brew; then
        echo "       brew install $pkg"
        brew install "$pkg" || return 1
        return 0
    fi
    return 1
}

# Linux 向けの代表的インストールコマンドを表示する
print_linux_instructions() {
    local tool="$1"
    echo ""
    echo "[INFO] $tool が見つかりませんでした。お使いのディストリビューションに合わせて手動でインストールしてください:"
    case "$tool" in
        yt-dlp)
            echo "  Debian/Ubuntu : sudo apt install yt-dlp"
            echo "  Fedora        : sudo dnf install yt-dlp"
            echo "  Arch          : sudo pacman -S yt-dlp"
            echo "  pip フォールバック: pip install --user yt-dlp"
            ;;
        ffmpeg)
            echo "  Debian/Ubuntu : sudo apt install ffmpeg"
            echo "  Fedora        : sudo dnf install ffmpeg"
            echo "  Arch          : sudo pacman -S ffmpeg"
            ;;
        whisper-cli)
            echo "  whisper.cpp はソースビルドが基本です。"
            echo "    git clone https://github.com/ggml-org/whisper.cpp"
            echo "    cd whisper.cpp && cmake -B build && cmake --build build --config Release"
            echo "    生成された build/bin/whisper-cli を PATH か $(pwd)/lib/tools/ にコピーしてください。"
            ;;
    esac
}

# --- 1. yt-dlp ---------------------------------------------------------
echo "[1/4] yt-dlp ..."
if have yt-dlp; then
    echo "       skip (already on PATH: $(command -v yt-dlp))"
elif [ -x "lib/tools/yt-dlp" ]; then
    echo "       skip (already in lib/tools/)"
else
    if [ "$OS" = "Darwin" ] && brew_install yt-dlp; then
        :
    else
        # ポータブルバイナリを直接取得(Linux/macOS兼用)
        require_curl
        echo "       downloading static binary into lib/tools/ ..."
        curl -L --fail \
            -o lib/tools/yt-dlp \
            https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
            && chmod +x lib/tools/yt-dlp \
            || { print_linux_instructions yt-dlp; ANY_MISSING=1; }
    fi
fi

# --- 2. ffmpeg ---------------------------------------------------------
echo "[2/4] ffmpeg ..."
if have ffmpeg; then
    echo "       skip (already on PATH: $(command -v ffmpeg))"
elif [ -x "lib/tools/ffmpeg" ]; then
    echo "       skip (already in lib/tools/)"
else
    if [ "$OS" = "Darwin" ] && brew_install ffmpeg; then
        :
    else
        # ffmpeg は配布形態がOS/アーキで分かれるため自動取得は難しい。手順を表示。
        print_linux_instructions ffmpeg
        ANY_MISSING=1
    fi
fi

# --- 3. whisper.cpp (whisper-cli) -------------------------------------
echo "[3/4] whisper-cli ..."
if have whisper-cli || have whisper-cpp; then
    echo "       skip (already on PATH)"
elif [ -x "lib/tools/whisper-cli" ] || [ -x "lib/tools/whisper-cpp" ] || [ -x "lib/tools/main" ]; then
    echo "       skip (already in lib/tools/)"
else
    if [ "$OS" = "Darwin" ] && brew_install whisper-cpp; then
        :
    else
        print_linux_instructions whisper-cli
        ANY_MISSING=1
    fi
fi

# --- 4. Whisper model --------------------------------------------------
echo "[4/4] ggml-base.bin (~140MB) ..."
if [ -f "lib/models/ggml-base.bin" ]; then
    echo "       skip (already exists)"
else
    require_curl
    curl -L --fail \
        -o lib/models/ggml-base.bin \
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
fi

# --- 結果サマリ --------------------------------------------------------
echo ""
echo "============================================================"
if [ "$ANY_MISSING" = "0" ]; then
    echo "  [OK] Whisper setup complete."
    echo "  - The app will now auto-transcribe YouTube videos"
    echo "    when subtitles are unavailable."
    echo "  - First run after install can be slow (model load)."
else
    echo "  [WARN] 一部のツールがインストールできませんでした。"
    echo "  上記の案内に従って手動でインストールしてから、もう一度このスクリプトを実行してください。"
fi
echo "============================================================"
