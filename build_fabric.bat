@echo off
setlocal

REM ============================================================
REM  Baritone Fabric - Auto Compile, Remap, and Reobfuscate
REM ============================================================
REM  This script:
REM    1. Compiles all source sets (api, main, launch)
REM    2. Creates a shadow/fat JAR with all dependencies
REM    3. Remaps the JAR (intermediary -> mojang mappings)
REM    4. Runs ProGuard to obfuscate (reobf) the JAR
REM    5. Creates the final distribution JAR in /dist
REM ============================================================

echo.
echo ==========================================
echo   Baritone Fabric Build Script
echo ==========================================
echo.

REM Check if JAVA_HOME is set
if not defined JAVA_HOME (
    echo [WARN] JAVA_HOME is not set.
    echo [INFO] Gradle will use the JDK specified in gradle.properties.
    echo [INFO] Currently configured: org.gradle.java.home=C:/Program Files/Java/jdk-21.0.10
    echo.
)

REM Step 1: Clean build artifacts (optional - uncomment if desired)
REM echo [STEP 1/3] Cleaning previous build artifacts...
REM call gradlew.bat clean
REM if %ERRORLEVEL% NEQ 0 (
REM     echo [ERROR] Clean failed!
REM     exit /b %ERRORLEVEL%
REM )
REM echo [DONE] Clean complete.
REM echo.

REM Step 1: Compile all source sets
echo [STEP 1/3] Compiling source sets (api, main, launch)...
call gradlew.bat :fabric:compileJava
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    exit /b %ERRORLEVEL%
)
echo [DONE] Compilation complete.
echo.

REM Step 2: Remap JAR (shadow + intermediary-to-mojmap remap)
echo [STEP 2/3] Creating shadow JAR and remapping (intermediary -^> mojmap)...
call gradlew.bat :fabric:remapJar
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Remap failed!
    exit /b %ERRORLEVEL%
)
echo [DONE] Remap complete.
echo.

REM Step 3: ProGuard obfuscation (reobf) + final distribution
echo [STEP 3/3] Running ProGuard obfuscation and creating distribution JAR...
call gradlew.bat :fabric:createDist
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] ProGuard / createDist failed!
    exit /b %ERRORLEVEL%
)
echo [DONE] ProGuard and distribution complete.
echo.

echo ==========================================
echo   BUILD SUCCESSFUL!
echo ==========================================
echo.
echo Output files:
echo   Remapped JAR:   fabric\build\libs\baritone-fabric-%version%.jar
echo   Distribution:   dist\baritone-api-fabric-%version%.jar
echo.
echo To run the full build in one command, use:
echo   gradlew :fabric:build
echo.

endlocal