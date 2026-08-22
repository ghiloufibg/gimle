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

set "classpath="
for %%j in ("%lib_dir%\*.jar") do (
  if defined classpath (
    set "classpath=!classpath!;%%j"
  ) else (
    set "classpath=%%j"
  )
)

if not defined classpath (
  echo hilmir: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\hilmir\ (present only when gimle-dist was built with its
rem dist-with-jre profile); otherwise fall back to plain "java" on PATH, exactly as before this
rem archive ever bundled a JRE of its own. This choice is purely about which java launches hilmir
rem itself -- it is unrelated to runtime.useBundledJre, the separate topology-level setting
rem controlling which java LaunchPlanner picks for each *spawned* process (store/muninn/andvari/
rem fafnir/control-plane; never agent/worker).
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
) else if exist "%script_dir%..\jre\hilmir\bin\java.exe" (
  set "java_bin=%script_dir%..\jre\hilmir\bin\java.exe"
) else (
  set "java_bin=java"
)

rem Lets hilmir locate this archive's own install root (in particular its modules\ directory, a
rem sibling of bin\ and lib\) without guessing from java.class.path.
set "GIMLE_HOME=%script_dir%.."

"%java_bin%" -cp "%classpath%" com.gimle.hilmir.HilmirMain %*
exit /b %ERRORLEVEL%
