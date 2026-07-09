@echo off
setlocal
cd /d "%~dp0..\..\"

REM ----------------------------------------------------------------------
REM   main.java.Recipe.Recipe Manager Pro - launcher (no console window)
REM   - Auto-builds the JAR on first run
REM   - Launches the GUI via javaw (detached, no console)
REM ----------------------------------------------------------------------

if not exist "RecipeManager.jar" (
    echo First-run setup: building...
    call "%~dp0build.bat"
    if errorlevel 1 exit /b 1
)

REM ---- Optional: Whisper offline transcription -------------------------
REM   Ask once on first launch. Skip silently if already set up,
REM   or if the user previously declined (whisper-skipped.flag).
if not exist "lib\tools\whisper-cli.exe" if not exist "whisper-skipped.flag" (
    echo.
    echo === Optional: Whisper offline transcription setup ===
    echo This adds local YouTube transcription when subtitles are missing.
    echo It will download about 300 MB of tools and a model file.
    choice /C YN /N /M "Install Whisper now? [Y/N] "
    if errorlevel 2 (
        echo Skipped. A flag file ^(whisper-skipped.flag^) was created so
        echo this prompt will not appear again. Delete that file or run
        echo setup-whisper.bat manually to install Whisper later.
        echo skipped> whisper-skipped.flag
    ) else (
        call "%~dp0setup-whisper.bat"
    )
)

where javaw >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javaw not found. Please install JRE/JDK.
    pause
    exit /b 1
)

start "" javaw -jar RecipeManager.jar

endlocal
