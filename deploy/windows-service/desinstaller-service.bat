@echo off
rem Lance uninstall-service.ps1 sans avoir a modifier la politique d'execution de PowerShell.
rem Ajoute -RemoveFiles pour supprimer aussi le dossier C:\MTG-Companion :
rem   desinstaller-service.bat -RemoveFiles
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-service.ps1" %*
