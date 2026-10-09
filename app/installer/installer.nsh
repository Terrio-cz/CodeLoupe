; Hooks of the Windows installer (electron-builder `nsis.include`).
;
; The daemon is a java.exe from resources\codeloupe\runtime that outlives the app. Files it holds open cannot be replaced
; or deleted, so install over an older version and uninstall stop it first. Only processes that run from this
; installation are stopped, never another CodeLoupe daemon on the machine.

; Only the uninstaller reads the command line; a variable the installer never uses is a warning, and warnings fail the build.
!ifdef BUILD_UNINSTALLER
  Var codeLoupeParams
  Var codeLoupeFlag
!endif

!macro stopCodeLoupeDaemon
  ; The installer is a 32-bit process and so is the PowerShell it starts, which cannot read the path of a 64-bit process
  ; through Get-Process; WMI reports it for every process.
  nsExec::Exec '$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $$_.ExecutablePath -and $$_.ExecutablePath.StartsWith(\"$INSTDIR\resources\codeloupe\", [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { Stop-Process -Id $$_.ProcessId -Force }"'
  Pop $0
  Sleep 500
!macroend

; The installer, before it removes the previous version (an update runs the old uninstaller with --updated).
!macro customInit
  !insertmacro stopCodeLoupeDaemon
!macroend

; The uninstaller, before it removes the files.
!macro customUnInstall
  !insertmacro stopCodeLoupeDaemon

  ; An update keeps the data and asks nothing; so does an uninstall started with /S. The one-click uninstaller runs
  ; its section in silent mode whatever the command line says, so /S is read from the command line itself.
  ${ifNot} ${isUpdated}
    ${GetParameters} $codeLoupeParams
    ClearErrors
    ${GetOptions} $codeLoupeParams "/S" $codeLoupeFlag
    ${if} ${Errors}
      ClearErrors
      MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 "Delete the CodeLoupe data as well?$\r$\n$\r$\nIndex cache, task mirror and the daemon's settings in $LOCALAPPDATA\codeloupe, and the app's own settings in $APPDATA\${APP_PACKAGE_NAME}.$\r$\n$\r$\nChoose No to keep them for a later install." IDNO keepCodeLoupeData
      RMDir /r "$LOCALAPPDATA\codeloupe"
      RMDir /r "$APPDATA\${APP_PACKAGE_NAME}"
      keepCodeLoupeData:
    ${endIf}
  ${endIf}
!macroend
