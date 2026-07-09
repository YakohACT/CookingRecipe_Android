@echo off
setlocal
cd /d "%~dp0..\..\"

REM ----------------------------------------------------------------------
REM   main.java.Recipe.Recipe Manager Pro - debug launcher (with console)
REM   Use this when you need stdout/stderr for troubleshooting.
REM ----------------------------------------------------------------------

if not exist "RecipeManager.jar" (
    echo RecipeManager.jar not found. Running build first...
    call "%~dp0build.bat"
    if errorlevel 1 exit /b 1
)

echo === Launching with console attached. Close the GUI to exit. ===
java -jar RecipeManager.jar

echo.
echo === Application exited. ===
pause
endlocal
