@echo off
rem Windows counterpart of oshi.sh.
rem
rem   oshi.cmd test                run the parity suite
rem   oshi.cmd run                 the protocol demo
rem   oshi.cmd run --args="--mesh" a live mesh node (mDNS discovery + TCP)
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
call "%~dp0gradlew.bat" %*
