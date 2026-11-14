; Flint's additions to Tauri's NSIS installer (tauri.conf.json bundle > windows > nsis > installerHooks).

; Tauri's template includes this file after `SetCompressor /SOLID lzma` and before any file goes in, so the LZMA window
; can still be set: 64 MB instead of NSIS's 8 MB holds the whole app at once, about 58 KB off the installer. Unpacking is
; as fast as before; the installer uses up to 64 MB of memory while it runs.
SetCompressorDictSize 64

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
