@rem Start script of the codeloupe CLI (replaces the one Gradle generates; build.gradle.kts copies it into bin\).
@rem
@rem The JVM maps an AOT cache kept under the CodeLoupe home instead of loading and verifying ~1500 classes on every call.
@rem The daemon makes the cache in the background a little after it started (codeloupe.aot.AotLauncher; -Dcodeloupe.aot
@rem names its files), one per install directory and build, so two installs do not keep replacing each other's. Until it is
@rem there a call runs on the JDK's own class-data archive: it takes ~100 ms longer, but there is nothing to dump at its end,
@rem and two calls that start together have no file to write together (a dynamic archive dumped by both was torn, and the
@rem next JVM that mapped it crashed).
@rem No parenthesised blocks below: a path such as "Program Files (x86)" would end them early.
@echo off
setlocal

for %%I in ("%~dp0..") do set "APP_HOME=%%~fI"

set "JAVA_EXE=java.exe"
@rem A bundled runtime (runtime\ next to bin\) wins over any JDK on the machine: the bundle needs none.
if exist "%APP_HOME%\runtime\bin\java.exe" set "JAVA_EXE=%APP_HOME%\runtime\bin\java.exe"
if exist "%APP_HOME%\runtime\bin\java.exe" goto haveJava
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME if not exist "%JAVA_EXE%" goto badJava
:haveJava

@rem Same place as ConfigLoader.defaultHome.
set "CL_HOME=%USERPROFILE%\AppData\Local\codeloupe"
if defined LOCALAPPDATA set "CL_HOME=%LOCALAPPDATA%\codeloupe"
if defined CODELOUPE_HOME set "CL_HOME=%CODELOUPE_HOME%"
set "KEY=%APP_HOME%"
set "KEY=%KEY:\=_%"
set "KEY=%KEY::=_%"
set "KEY=%KEY: =_%"
set "KEY=%KEY:.=_%"
set "KEY=%KEY:~-60%"
if not exist "%CL_HOME%\aot" mkdir "%CL_HOME%\aot" 2>nul
set "AOT=%CL_HOME%\aot\%KEY%-@BUILD@"

@rem A JVM that cannot use the cache (another JDK, a damaged header) runs without it. -Xshare cannot be given with it.
set "CACHE=-XX:-UsePerfData"
if exist "%AOT%.cli.aot" set "CACHE=-XX:AOTCache=%AOT%.cli.aot"

@rem The CLI is short-lived: small heap, no C2, serial GC (codeloupe.cli.CliJvm lists these flags too). -Xlog:disable keeps
@rem JVM notes (a cache that no longer fits) out of the output; the JVM then simply runs without it.
"%JAVA_EXE%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Xmx128m -XX:-UsePerfData ^
    "%CACHE%" -Xlog:disable "-Dcodeloupe.aot=%AOT%" ^
    %JAVA_OPTS% %CODELOUPE_OPTS% -jar "%APP_HOME%\lib\@JAR@" %*
exit /b %ERRORLEVEL%

:badJava
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1
