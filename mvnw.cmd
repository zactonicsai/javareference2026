@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup script (Windows)
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set BASE_DIR=%~dp0
set BASE_DIR=%BASE_DIR:~0,-1%
set WRAPPER_JAR=%BASE_DIR%\.mvn\wrapper\maven-wrapper.jar
set WRAPPER_PROPS=%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties

if not exist "%WRAPPER_PROPS%" (
    echo ERROR: Missing %WRAPPER_PROPS% 1>&2
    exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%a in ("%WRAPPER_PROPS%") do (
    if /I "%%a"=="wrapperUrl" set WRAPPER_URL=%%b
)

if "%WRAPPER_URL%"=="" (
    echo ERROR: wrapperUrl not set in maven-wrapper.properties 1>&2
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo Downloading Maven Wrapper from %WRAPPER_URL% ...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
    if errorlevel 1 (
        echo ERROR: Download failed. 1>&2
        exit /b 1
    )
)

set JAVA_EXE=java.exe
if not "%JAVA_HOME%"=="" set JAVA_EXE=%JAVA_HOME%\bin\java.exe

"%JAVA_EXE%" %MAVEN_OPTS% -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%" org.apache.maven.wrapper.MavenWrapperMain %*
