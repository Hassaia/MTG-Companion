<#
.SYNOPSIS
    Désinstalle le service Windows MTG Companion.

.DESCRIPTION
    Arrête le service, le supprime et retire la règle du pare-feu.
    Le dossier d'installation est conservé (il peut contenir des données
    ou des journaux), sauf si -RemoveFiles est indiqué.

.PARAMETER InstallDir
    Dossier d'installation. Par défaut : C:\MTG-Companion

.PARAMETER RemoveFiles
    Supprime aussi le dossier d'installation.

.EXAMPLE
    .\uninstall-service.ps1 -RemoveFiles
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\MTG-Companion',
    [switch]$RemoveFiles
)

$ErrorActionPreference = 'Stop'
$ServiceId   = 'mtg-companion'
$ServiceName = 'MTG Companion'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host 'Droits administrateur nécessaires, relance du script...'
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "`"$PSCommandPath`"",
                 '-InstallDir', "`"$InstallDir`"")
    if ($RemoveFiles) { $argList += '-RemoveFiles' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
    exit
}

$serviceExe = Join-Path $InstallDir "$ServiceId.exe"
$service = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue

if ($service) {
    Write-Host "Arrêt du service..."
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceId -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    Write-Host "Suppression du service..."
    if (Test-Path $serviceExe) { & $serviceExe uninstall | Out-Host }
    else { sc.exe delete $ServiceId | Out-Host }
} else {
    Write-Host "Le service n'est pas installé."
}

Get-NetFirewallRule -DisplayName $ServiceName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host "Règle du pare-feu retirée."

if ($RemoveFiles -and (Test-Path $InstallDir)) {
    # Laisse à Windows le temps de libérer les fichiers.
    Start-Sleep -Seconds 2
    Remove-Item -Path $InstallDir -Recurse -Force
    Write-Host "Dossier $InstallDir supprimé."
} elseif (Test-Path $InstallDir) {
    Write-Host "Dossier $InstallDir conservé (relance avec -RemoveFiles pour le supprimer)."
}

Write-Host "`nTerminé." -ForegroundColor Green
