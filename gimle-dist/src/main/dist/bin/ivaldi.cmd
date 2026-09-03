@echo off
rem Launches Ivaldi (the cluster designer's backend) against every jar in this archive's own
rem lib\ directory, regardless of the caller's current working directory. Windows counterpart of
rem the bin/ivaldi sh script -- keep both in sync. IvaldiMain reads no positional arguments at
rem all, only -D system properties (gimle.ivaldi.port/dataRoot/host), so %* is placed before the
rem main class below rather than after it, unlike gimle.cmd/hilmir.cmd: a -D flag is a JVM option
rem and only ever takes effect there.
rem   ivaldi -Dgimle.ivaldi.port=9097 -Dgimle.ivaldi.dataRoot=C:\gimle-ivaldi-data
setlocal enabledelayedexpansion

set "script_dir=%~dp0"
set "lib_dir=%script_dir%..\lib"

rem Built via pushd + an unquoted "*.jar" pattern rather than a quoted "%lib_dir%\*.jar" pattern:
rem cmd.exe's basic FOR treats a quoted set item as a literal string and does not wildcard-expand
rem it at all, so quoting the whole pattern (an easy mistake -- it looks identical to the sh
rem script's own "$lib_dir"/*.jar) would silently match nothing. pushd resolves lib_dir as one
rem argument regardless of spaces in the install path (unlike an unquoted path, which FOR would
rem otherwise split on each space), and %%~fj recovers each match's fully-qualified path so the
rem resulting classpath is correct even after popd restores the caller's original directory.
set "classpath="
pushd "%lib_dir%" >nul 2>&1
if errorlevel 1 (
  echo ivaldi: no jars found under %lib_dir% 1>&2
  exit /b 1
)
for %%j in (*.jar) do (
  if defined classpath (
    set "classpath=!classpath!;%%~fj"
  ) else (
    set "classpath=%%~fj"
  )
)
popd

if not defined classpath (
  echo ivaldi: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\ivaldi\ (present only when gimle-dist was built with its
rem dist-with-jre profile); otherwise resolve java.exe off PATH, exactly as before this archive
rem ever bundled a JRE of its own. The PATH branch uses the %%~$PATH:e modifier to resolve a
rem fully-qualified path up front rather than invoking the bare "java" token directly: every
rem branch below then always quotes a real path, with no special-casing needed for a bareword
rem command name relying on its own separate PATH-search behavior at invocation time.
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
  if not exist "!java_bin!" (
    echo ivaldi: JAVA_HOME is set to "%JAVA_HOME%" but !java_bin! does not exist -- fix or unset JAVA_HOME 1>&2
    exit /b 1
  )
  rem An explicit JAVA_HOME overriding another, correct JDK on the machine is exactly the case most
  rem likely to point at a too-old one -- caught here, up front, rather than surfacing later as a
  rem bare "class file version 69.0, this version of the Java Runtime only recognizes ... up to
  rem 65.0" from deep inside whatever this script launches. Filtered through findstr for a line
  rem containing "version" rather than blindly taking -version's first output line: a
  rem JAVA_TOOL_OPTIONS/_JAVA_OPTIONS set in the environment makes the JVM print a "Picked up ..."
  rem diagnostic line ahead of the real one, e.g. openjdk version "25" 2025-09-16 -- swapping the
  rem quotes for spaces then turns the quoted version number into its own whitespace-delimited token.
  set "version_line="
  for /f "usebackq delims=" %%v in (`"!java_bin!" -version 2^>^&1 ^| findstr /i "version"`) do if not defined version_line set "version_line=%%v"
  set "version_line=!version_line:"= !"
  set "java_version="
  for /f "tokens=3" %%v in ("!version_line!") do set "java_version=%%v"
  set "java_major="
  for /f "tokens=1 delims=." %%m in ("!java_version!") do set "java_major=%%m"
  if not defined java_major set "java_major=0"
  if !java_major! lss 25 (
    echo ivaldi: JAVA_HOME is set to "%JAVA_HOME%", but !java_bin! reports "!version_line!" -- Gimle requires JDK 25+; point JAVA_HOME at a JDK 25+ install or unset it 1>&2
    exit /b 1
  )
) else if exist "%script_dir%..\jre\ivaldi\bin\java.exe" (
  set "java_bin=%script_dir%..\jre\ivaldi\bin\java.exe"
) else (
  for %%e in (java.exe) do set "java_bin=%%~$PATH:e"
  if not defined java_bin set "java_bin=java"
)

"%java_bin%" -cp "%classpath%" %* com.gimle.ivaldi.IvaldiMain
exit /b %ERRORLEVEL%
