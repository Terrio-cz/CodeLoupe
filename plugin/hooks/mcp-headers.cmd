:; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) b=$(command -v sh); b="$(dirname "$b")/bash";; *) b=bash;; esac; exec "$b" "$(dirname "$0")/mcp-headers.sh"
@echo off
rem headersHelper of the plugin MCP entry: one file for both shells Claude Code may hand it to (.mcp.json runs sh on it first, then the file itself).
rem sh (macOS, Linux): the first line starts mcp-headers.sh. cmd.exe (Windows has no sh): the first line is a label and the rest runs.
rem Claude Code runs a headersHelper through cmd.exe, where a bare bash is the WSL launcher in System32 and cannot read a Windows path.
rem So look for the Git Bash that Claude Code itself needs on Windows, and never fall back to whatever bash happens to be.
rem Never fails and never blocks: without Git Bash the plain header goes out, with which the read-only tools work.
setlocal
set "GB="
if defined CLAUDE_CODE_GIT_BASH_PATH if exist "%CLAUDE_CODE_GIT_BASH_PATH%" set "GB=%CLAUDE_CODE_GIT_BASH_PATH%"
if not defined GB for %%D in ("%ProgramFiles%" "%ProgramW6432%" "%LOCALAPPDATA%\Programs") do if not defined GB if exist "%%~D\Git\bin\bash.exe" set "GB=%%~D\Git\bin\bash.exe"
if not defined GB for /f "delims=" %%G in ('where git 2^>nul') do if not defined GB if exist "%%~dpG..\bin\bash.exe" set "GB=%%~dpG..\bin\bash.exe"
if defined GB "%GB%" "%~dp0mcp-headers.sh" && exit /b 0
echo {"x-codeloupe":"1"}
exit /b 0
