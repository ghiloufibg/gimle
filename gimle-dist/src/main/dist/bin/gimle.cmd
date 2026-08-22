@echo off
rem Launches the gimle CLI against every jar in this archive's own lib\ directory, regardless of
rem the caller's current working directory. Windows counterpart of the bin/gimle sh script --
rem keep both in sync.
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
  echo gimle: no jars found under %lib_dir% 1>&2
  exit /b 1
)

rem Precedence: an explicit JAVA_HOME is a deliberate operator override and always wins; otherwise
rem prefer this archive's own bundled jre\cli\ (present only when gimle-dist was built with its
rem dist-with-jre profile); otherwise fall back to plain "java" on PATH, exactly as before this
rem archive ever bundled a JRE of its own.
if defined JAVA_HOME (
  set "java_bin=%JAVA_HOME%\bin\java.exe"
) else if exist "%script_dir%..\jre\cli\bin\java.exe" (
  set "java_bin=%script_dir%..\jre\cli\bin\java.exe"
) else (
  set "java_bin=java"
)

"%java_bin%" -cp "%classpath%" com.gimle.cli.GimleCli %*
exit /b %ERRORLEVEL%
