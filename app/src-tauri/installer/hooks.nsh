; Flint's additions to Tauri's NSIS installer (tauri.conf.json bundle > windows > nsis > installerHooks).

; "Delete the application data" on uninstall: Tauri's own step removes the folders named after the bundle identifier
; (com.flintclient.desktop), which older versions of Flint used; Flint's data folder is now "Flint Client"
; (src/datadir.rs). Only when the player ticked the box, and never during an update.
!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    SetShellVarContext current
    RmDir /r "$APPDATA\Flint Client"
  ${EndIf}
!macroend
