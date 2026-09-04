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
  if not exist "!java_bin!" (
    echo hilmir: JAVA_HOME is set to "%JAVA_HOME%" but !java_bin! does not exist -- fix or unset JAVA_HOME 1>&2
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
  rem The extra pair of quotes immediately inside the backticks (right after the opening backtick
  rem and right before the closing one) is not redundant: FOR /F runs a backquoted command via its
  rem own "cmd /c" child, and cmd's own /c argument handling strips exactly the command line's
  rem first character and its last quote character whenever the line has more than two quote
  rem characters total -- true here, since the quoted java_bin path and findstr's own quoted search
  rem term already add up to four. Without the sacrificial outer pair, that strips the real leading
  rem quote off the java_bin path (corrupting it every time this branch runs at all, not only when
  rem JAVA_HOME itself contains a space) instead of the sacrificial one, so java -version never
  rem actually runs as intended and version_line silently ends up empty.
  set "version_line="
  for /f "usebackq delims=" %%v in (`""!java_bin!" -version 2^>^&1 ^| findstr /i "version""`) do if not defined version_line set "version_line=%%v"
  set "version_line=!version_line:"= !"
  set "java_version="
  for /f "tokens=3" %%v in ("!version_line!") do set "java_version=%%v"
  set "java_major="
  for /f "tokens=1 delims=." %%m in ("!java_version!") do set "java_major=%%m"
  if not defined java_major set "java_major=0"
  if !java_major! lss 25 (
    echo hilmir: JAVA_HOME is set to "%JAVA_HOME%", but !java_bin! reports "!version_line!" -- Gimle requires JDK 25+; point JAVA_HOME at a JDK 25+ install or unset it 1>&2
    exit /b 1
  )
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
