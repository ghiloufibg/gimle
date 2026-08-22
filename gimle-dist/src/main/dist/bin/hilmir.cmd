@echo off
rem Launches Hilmir (Gimle's cluster bootstrap/deployment tool) against every jar in this
rem archive's own lib\ directory, regardless of the caller's current working directory. Windows
rem counterpart of the bin/hilmir sh script -- keep both in sync.
rem
rem Whatever classpath is built here becomes the default classpath LaunchPlanner writes into every
rem process command "hilmir up" spawns (StoreMain, ControlPlaneMain, AgentMain, WorkerMain, and the
rem rest), unless a topology document sets runtime.classpath itself: HilmirMain falls back to the
rem running JVM's own java.class.path when a topology leaves that field unset, so this script's own
rem -cp argument doubles as the default classpath every role launches with.
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
  echo hilmir: no jars found under %lib_dir% 1>&2
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
  echo hilmir: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\hilmir\ (present only when gimle-dist was built with its
rem dist-with-jre profile); otherwise resolve java.exe off PATH, exactly as before this archive
rem ever bundled a JRE of its own. This choice is purely about which java launches hilmir itself --
rem it is unrelated to runtime.useBundledJre, the separate topology-level setting controlling which
rem java LaunchPlanner picks for each *spawned* process (store/muninn/andvari/fafnir/control-plane;
rem never agent/worker). The PATH branch uses the %%~$PATH:e modifier to resolve a fully-qualified
rem path up front rather than invoking the bare "java" token directly: every branch below then
rem always quotes a real path, with no special-casing needed for a bareword command name relying
rem on its own separate PATH-search behavior at invocation time.
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
) else if exist "%script_dir%..\jre\hilmir\bin\java.exe" (
  set "java_bin=%script_dir%..\jre\hilmir\bin\java.exe"
) else (
  for %%e in (java.exe) do set "java_bin=%%~$PATH:e"
  if not defined java_bin set "java_bin=java"
)

rem Lets hilmir locate this archive's own install root (in particular its modules\ directory, a
rem sibling of bin\ and lib\) without guessing from java.class.path.
set "GIMLE_HOME=%script_dir%.."

"%java_bin%" -cp "%classpath%" com.gimle.hilmir.HilmirMain %*
exit /b %ERRORLEVEL%
