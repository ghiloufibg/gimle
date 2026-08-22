@echo off
rem Launches the gimle CLI against every jar in this archive's own lib\ directory, regardless of
rem the caller's current working directory. Windows counterpart of the bin/gimle sh script --
rem keep both in sync.
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
  echo gimle: no jars found under %lib_dir% 1>&2
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
  echo gimle: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\cli\ (present only when gimle-dist was built with its
rem dist-with-jre profile); otherwise resolve java.exe off PATH, exactly as before this archive
rem ever bundled a JRE of its own. The PATH branch uses the %%~$PATH:e modifier to resolve a
rem fully-qualified path up front rather than invoking the bare "java" token directly: every
rem branch below then always quotes a real path, with no special-casing needed for a bareword
rem command name relying on its own separate PATH-search behavior at invocation time.
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
) else if exist "%script_dir%..\jre\cli\bin\java.exe" (
  set "java_bin=%script_dir%..\jre\cli\bin\java.exe"
) else (
  for %%e in (java.exe) do set "java_bin=%%~$PATH:e"
  if not defined java_bin set "java_bin=java"
)

"%java_bin%" -cp "%classpath%" com.gimle.cli.GimleCli %*
exit /b %ERRORLEVEL%
