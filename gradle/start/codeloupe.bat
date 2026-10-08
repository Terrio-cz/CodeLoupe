@rem Start script of the codeloupe CLI (replaces the one Gradle generates; build.gradle.kts copies it into bin\).
@rem
@rem The JVM maps a class-data archive kept under the CodeLoupe home instead of loading and verifying ~1500 classes on
@rem every call. It creates the archive itself on the first run (that run is slower) and again whenever the jars or the
@rem JDK change. One archive per install directory, so two installs do not keep replacing each other's.
@rem No parenthesised blocks below: a path such as "Program Files (x86)" would end them early.
@echo off
setlocal

for %%I in ("%~dp0..") do set "APP_HOME=%%~fI"

set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME if not exist "%JAVA_EXE%" goto badJava

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
if not exist "%CL_HOME%\cds" mkdir "%CL_HOME%\cds" 2>nul

@rem A JVM that cannot write the archive it wants to create exits with 127, so without a writable directory it runs
@rem without one.
set "CDS1=-Xshare:auto"
set "CDS2=-Xshare:auto"
set "ARCHIVE=%CL_HOME%\cds\%KEY%-@BUILD@.jsa"
(type nul >>"%CL_HOME%\cds\.probe") 2>nul && set "CDS1=-XX:+AutoCreateSharedArchive" && set "CDS2=-XX:SharedArchiveFile=%ARCHIVE%"
@rem A JVM does not rebuild an archive whose jars changed, it just stops using it: every build has an archive of its own.
if "%CDS1%"=="-XX:+AutoCreateSharedArchive" for %%F in ("%CL_HOME%\cds\%KEY%-*.jsa") do if /i not "%%~fF"=="%ARCHIVE%" del "%%~fF" 2>nul

@rem The CLI is short-lived: small heap, no C2, serial GC. -Xlog:disable keeps JVM notes (an archive that no longer
@rem fits) out of the output; the JVM then simply runs without it.
"%JAVA_EXE%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Xss512k -Xmx128m -XX:-UsePerfData ^
    "%CDS1%" "%CDS2%" -Xlog:disable ^
    %JAVA_OPTS% %CODELOUPE_OPTS% -jar "%APP_HOME%\lib\@JAR@" %*
exit /b %ERRORLEVEL%

:badJava
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1
