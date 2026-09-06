@echo off
rem Launches Ragnarok (Gimle's chaos/stress-testing tool) against every jar in this archive's own
rem lib\ directory, regardless of the caller's current working directory. Windows counterpart of
rem the bin/ragnarok sh script -- keep both in sync.
setlocal enabledelayedexpansion

set "script_dir=%~dp0"
set "lib_dir=%script_dir%..\lib"

rem Built via pushd + an unquoted "*.jar" pattern rather than a quoted "%lib_dir%\*.jar" pattern:
rem cmd.exe's basic FOR treats a quoted set item as a literal string and does not wildcard-expand
rem it at all, so quoting the whole pattern would silently match nothing. pushd resolves lib_dir as
rem one argument regardless of spaces in the install path, and %%~fj recovers each match's
rem fully-qualified path so the resulting classpath is correct even after popd restores the
rem caller's original directory.
set "classpath="
pushd "%lib_dir%" >nul 2>&1
if errorlevel 1 (
  echo ragnarok: no jars found under %lib_dir% 1>&2
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
  echo ragnarok: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\ragnarok\ (present only when gimle-dist was built with
rem its dist-with-jre profile); otherwise resolve java.exe off PATH, the same precedence
rem bin/hilmir.cmd already establishes. The PATH branch uses the %%~$PATH:e modifier to resolve a
rem fully-qualified path up front rather than invoking the bare "java" token directly: every branch
rem below then always quotes a real path, with no special-casing needed for a bareword command name
rem relying on its own separate PATH-search behavior at invocation time.
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
  if not exist "!java_bin!" (
    echo ragnarok: JAVA_HOME is set to "%JAVA_HOME%" but !java_bin! does not exist -- fix or unset JAVA_HOME 1>&2
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
    echo ragnarok: JAVA_HOME is set to "%JAVA_HOME%", but !java_bin! reports "!version_line!" -- Gimle requires JDK 25+; point JAVA_HOME at a JDK 25+ install or unset it 1>&2
    exit /b 1
  )
) else if exist "%script_dir%..\jre\ragnarok" (
  set "java_bin=%script_dir%..\jre\ragnarok\bin\java.exe"
  rem A bundled runtime only ever runs on the one platform it was built for (jre\PLATFORM records
  rem which), and an archive travels further than the machine that built it. A runtime built for
  rem any other platform has no java.exe here at all, so rather than falling through to PATH
  rem without a word, say what was found and why it is being ignored. Keep in sync with the
  rem bin/ragnarok sh script.
  if not exist "!java_bin!" (
    set "bundled_platform=an unrecorded platform"
    if exist "%script_dir%..\jre\PLATFORM" (
      for /f "usebackq delims=" %%p in ("%script_dir%..\jre\PLATFORM") do set "bundled_platform=%%p"
    )
    echo ragnarok: this archive's bundled JRE ^(built for !bundled_platform!^) does not run on this machine -- ignoring it and looking for a java of this machine's own 1>&2
    for %%e in (java.exe) do set "java_bin=%%~$PATH:e"
    if not defined java_bin set "java_bin=java"
  )
) else (
  for %%e in (java.exe) do set "java_bin=%%~$PATH:e"
  if not defined java_bin set "java_bin=java"
)

rem Nothing above resolved to a real path: no JAVA_HOME, no usable bundled runtime, and no java.exe
rem on PATH. Said here, where the reason is still known, rather than left to surface as cmd.exe's
rem own "'java' is not recognized" from the launch line below.
if "%java_bin%"=="java" (
  echo ragnarok: no Java runtime to run on -- put a JDK 25+ java.exe on PATH, or set JAVA_HOME to a JDK 25+ install 1>&2
  exit /b 1
)

"%java_bin%" -cp "%classpath%" com.gimle.ragnarok.RagnarokMain %*
exit /b %ERRORLEVEL%
