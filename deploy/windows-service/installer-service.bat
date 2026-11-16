@echo off
rem Lance install-service.ps1 sans avoir a modifier la politique d'execution de PowerShell.
rem Double-clic suffit : le script demande lui-meme les droits administrateur.
rem Les parametres eventuels sont transmis tels quels, par exemple :
rem   installer-service.bat -Port 9000
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-service.ps1" %*
