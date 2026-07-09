@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\"

REM ----------------------------------------------------------------------
REM   Recipe Manager Pro - build script
REM   Compiles src/main/java recursively and produces RecipeManager.jar
REM ----------------------------------------------------------------------

where javac >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 17 or later.
    echo         https://adoptium.net/
    pause
    exit /b 1
)
where jar >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jar command not found. Please install JDK 17 or later.
    pause
    exit /b 1
)

if exist "out" rmdir /s /q out
mkdir out

set LIBS=lib\sqlite-jdbc-3.45.3.0.jar;lib\slf4j-api-2.0.13.jar;lib\slf4j-nop-2.0.13.jar;lib\flatlaf-3.4.1.jar

REM Collect all .java files into a temporary list (handles nested packages)
echo [1/3] Compiling sources...
if exist sources.tmp del sources.tmp
for /r "src" %%f in (*.java) do (
    echo %%f>> sources.tmp
)
javac -encoding UTF-8 -cp "%LIBS%" -d out -sourcepath src @sources.tmp
set RC=%errorlevel%
del sources.tmp
if not "%RC%"=="0" goto fail

echo [2/3] Writing manifest...
> manifest.tmp echo Manifest-Version: 1.0
>> manifest.tmp echo Main-Class: main.java.SwingMain
>> manifest.tmp echo Class-Path: lib/sqlite-jdbc-3.45.3.0.jar lib/slf4j-api-2.0.13.jar lib/slf4j-nop-2.0.13.jar lib/flatlaf-3.4.1.jar
>> manifest.tmp echo.

echo [3/3] Packaging RecipeManager.jar...
jar cfm RecipeManager.jar manifest.tmp -C out .
del manifest.tmp
if errorlevel 1 goto fail

echo.
echo ============================================================
echo   [OK] Build done. RecipeManager.jar is ready.
echo   - Double-click RecipeManager.jar (if associated with javaw)
echo   - Or run.bat (recommended)
echo ============================================================
echo.
pause
exit /b 0

:fail
if exist sources.tmp del sources.tmp
if exist manifest.tmp del manifest.tmp
echo.
echo [ERROR] Build failed. See messages above.
pause
exit /b 1
