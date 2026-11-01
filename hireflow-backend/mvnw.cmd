@echo off
setlocal

@REM ?? Resolve project-local JDK (if present) ??
for /d %%G in ("%~dp0.jdk\*") do (
    set "JAVA_HOME=%%G"
    goto :jdk_resolved
)
:jdk_resolved

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set and no project-local .jdk found.
    exit /b 1
)

set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo ERROR: java.exe not found at %JAVA_EXE%
    exit /b 1
)

@REM ?? Maven Wrapper bootstrap ??
set "WRAPPER_PROPERTIES=%~dp0.mvn\wrapper\maven-wrapper.properties"

@REM Read distributionUrl from properties
for /f "usebackq tokens=1,* delims==" %%a in ("%WRAPPER_PROPERTIES%") do (
    if "%%a"=="distributionUrl" set "DIST_URL=%%b"
)

@REM Determine Maven home inside user .m2
set "DIST_NAME="
for %%f in ("%DIST_URL%") do set "DIST_NAME=%%~nf"
set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\%DIST_NAME%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Downloading Maven distribution...
    set "DIST_ZIP=%TEMP%\%DIST_NAME%.zip"
    powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%TEMP%\%DIST_NAME%.zip'"
    if not exist "%MAVEN_HOME%" mkdir "%MAVEN_HOME%"
    powershell -Command "Expand-Archive -Path '%TEMP%\%DIST_NAME%.zip' -DestinationPath '%MAVEN_HOME%' -Force"
    @REM The zip contains a subfolder; flatten if needed
    for /d %%d in ("%MAVEN_HOME%\apache-maven-*") do (
        xcopy /E /Y /Q "%%d\*" "%MAVEN_HOME%\" >nul
        rmdir /S /Q "%%d" 2>nul
    )
)

@REM ?? Run Maven ??
"%MAVEN_HOME%\bin\mvn.cmd" %*
endlocal