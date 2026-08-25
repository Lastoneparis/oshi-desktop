@echo off
rem Windows launcher. The Linux counterpart is platform\linux\oshi.sh; the oshi.sh at the
rem repository root is the macOS one.
rem
rem Run it from anywhere - it resolves gradlew relative to ITSELF, two levels up:
rem
rem   platform\windows\oshi.cmd test                run the parity suite
rem   platform\windows\oshi.cmd run --args="--client"
rem   platform\windows\oshi.cmd packageMsi          needs WiX Toolset v3 on PATH
rem
rem Gradle needs a JDK before it can start. Most Windows boxes have JAVA_HOME set; if not,
rem this looks in the two places installers use. Nothing else in the build is OS-specific.

setlocal
if not "%JAVA_HOME%"=="" goto haveJava

for %%D in (
  "%ProgramFiles%\Eclipse Adoptium\jdk-21"
  "%ProgramFiles%\Eclipse Adoptium\jdk-17"
  "%ProgramFiles%\Microsoft\jdk-21"
  "%ProgramFiles%\Microsoft\jdk-17"
  "%ProgramFiles%\Java\jdk-21"
  "%ProgramFiles%\Java\jdk-17"
) do (
  if exist "%%~D\bin\java.exe" (
    set "JAVA_HOME=%%~D"
    goto haveJava
  )
)

where java >nul 2>nul
if %ERRORLEVEL%==0 goto haveJava

echo No JDK found. Install one (winget install EclipseAdoptium.Temurin.17.JDK) or set JAVA_HOME. 1>&2
exit /b 1

:haveJava
if not "%JAVA_HOME%"=="" echo Using JAVA_HOME=%JAVA_HOME%
call "%~dp0..\..\gradlew.bat" %*
