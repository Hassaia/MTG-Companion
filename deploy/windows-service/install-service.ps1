<#
.SYNOPSIS
    Installe (ou met à jour) MTG Companion comme service Windows.

.DESCRIPTION
    Le service démarre automatiquement avec Windows, avant toute ouverture
    de session. Il suffit d'allumer le PC pour que l'application soit
    accessible depuis le réseau local.

    Étapes réalisées :
      1. relance le script en administrateur si nécessaire ;
      2. trouve le jar Spring Boot (target\ ou build\libs\) et java.exe ;
      3. récupère WinSW (le programme qui fait tourner le jar en service) ;
      4. arrête et supprime l'ancien service s'il existe (mise à jour) ;
      5. copie les fichiers dans le dossier d'installation ;
      6. génère mtg-companion.xml à partir du modèle ;
      7. installe et démarre le service ;
      8. ouvre le port dans le pare-feu Windows ;
      9. attend que l'application réponde et affiche les adresses d'accès.

    Le script peut être relancé autant de fois que nécessaire : c'est aussi
    la façon de déployer une nouvelle version du jar.

.PARAMETER JarPath
    Chemin du jar à installer. Par défaut : le jar le plus récent trouvé
    dans target\ ou build\libs\ à la racine du dépôt.

.PARAMETER JavaPath
    Chemin de java.exe. Par défaut : %JAVA_HOME%\bin\java.exe, sinon le
    java trouvé dans le PATH.

.PARAMETER InstallDir
    Dossier d'installation. Par défaut : C:\MTG-Companion

.PARAMETER Port
    Port HTTP de l'application. Par défaut : 8080

.EXAMPLE
    .\install-service.ps1

.EXAMPLE
    .\install-service.ps1 -Port 9000 -JarPath D:\builds\mtg-companion-1.2.jar
#>
[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$JavaPath,
    [string]$InstallDir = 'C:\MTG-Companion',
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'

$ServiceId   = 'mtg-companion'
$ServiceName = 'MTG Companion'
$WinSWUrl    = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
$RepoRoot    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Write-Step($message) { Write-Host "`n==> $message" -ForegroundColor Cyan }
function Write-Ok($message)   { Write-Host "    $message" -ForegroundColor Green }
function Write-Warn($message) { Write-Host "    $message" -ForegroundColor Yellow }

# --- 1. Droits administrateur ------------------------------------------------
# Installer un service et modifier le pare-feu demandent des droits administrateur.
# Si le script n'en a pas, il se relance lui-même via l'invite UAC.
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host 'Droits administrateur nécessaires, relance du script...'
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "`"$PSCommandPath`"")
    foreach ($entry in $PSBoundParameters.GetEnumerator()) {
        $argList += "-$($entry.Key)"
        $argList += "`"$($entry.Value)`""
    }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
    exit
}

# --- 2. Recherche du jar et de Java -----------------------------------------
Write-Step 'Recherche du jar'
if (-not $JarPath) {
    $candidates = @('target', 'build\libs') |
        ForEach-Object { Join-Path $RepoRoot $_ } |
        Where-Object { Test-Path $_ } |
        ForEach-Object { Get-ChildItem -Path $_ -Filter '*.jar' -File } |
        # On écarte les jars qui ne sont pas exécutables (Gradle "-plain", sources, javadoc)
        Where-Object { $_.Name -notmatch '-(plain|sources|javadoc)\.jar$' } |
        Sort-Object LastWriteTime -Descending
    if (-not $candidates) {
        throw "Aucun jar trouvé dans target\ ou build\libs\. Construis d'abord le projet (mvnw package ou gradlew bootJar), ou indique -JarPath."
    }
    $JarPath = $candidates[0].FullName
}
if (-not (Test-Path $JarPath)) { throw "Jar introuvable : $JarPath" }
Write-Ok "Jar : $JarPath"

Write-Step 'Recherche de Java'
if (-not $JavaPath) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $JavaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $cmd) { throw "java.exe introuvable. Définis JAVA_HOME ou indique -JavaPath." }
        $JavaPath = $cmd.Source
    }
}
if (-not (Test-Path $JavaPath)) { throw "java.exe introuvable : $JavaPath" }
# Le chemin complet est écrit dans la configuration du service : celui-ci tourne
# sous le compte système et ne voit pas forcément le même PATH que toi.
Write-Ok "Java : $JavaPath"

# --- 3. WinSW -----------------------------------------------------------------
Write-Step 'Préparation de WinSW'
$localWinSW = Join-Path $PSScriptRoot 'WinSW-x64.exe'
$tempWinSW  = Join-Path $env:TEMP 'WinSW-x64.exe'
if (Test-Path $localWinSW) {
    # Permet de fournir WinSW à la main si le PC n'a pas accès à GitHub.
    $sourceWinSW = $localWinSW
    Write-Ok "WinSW trouvé à côté du script."
} else {
    Write-Ok "Téléchargement depuis $WinSWUrl"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $WinSWUrl -OutFile $tempWinSW -UseBasicParsing
    $sourceWinSW = $tempWinSW
}

# --- 4. Suppression de l'ancien service (mise à jour) ------------------------
$serviceExe = Join-Path $InstallDir "$ServiceId.exe"
$existing = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
if ($existing) {
    Write-Step 'Service existant détecté : arrêt et suppression avant mise à jour'
    if ($existing.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceId -Force
        $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    if (Test-Path $serviceExe) { & $serviceExe uninstall | Out-Host }
    else { sc.exe delete $ServiceId | Out-Host }
    # Windows peut mettre quelques secondes à supprimer réellement le service.
    for ($i = 0; $i -lt 20 -and (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue) {
        throw "L'ancien service n'a pas pu être supprimé. Ferme la console Services (services.msc) si elle est ouverte, puis relance le script."
    }
    Write-Ok 'Ancien service supprimé.'
}

# --- 5. Copie des fichiers ----------------------------------------------------
Write-Step "Copie des fichiers dans $InstallDir"
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
# Le jar est toujours renommé mtg-companion.jar : la configuration n'a pas à changer d'une version à l'autre.
Copy-Item -Path $JarPath -Destination (Join-Path $InstallDir 'mtg-companion.jar') -Force
Copy-Item -Path $sourceWinSW -Destination $serviceExe -Force
Write-Ok 'Fichiers copiés.'

# --- 6. Configuration du service ---------------------------------------------
Write-Step 'Génération de mtg-companion.xml'
$template = Get-Content -Path (Join-Path $PSScriptRoot 'mtg-companion.xml.template') -Raw -Encoding UTF8
$config = $template.Replace('@JAVA@', $JavaPath).Replace('@PORT@', [string]$Port)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $InstallDir "$ServiceId.xml"), $config, $utf8NoBom)
Write-Ok 'Configuration écrite.'

# --- 7. Installation et démarrage --------------------------------------------
Write-Step 'Installation du service'
& $serviceExe install | Out-Host
if ($LASTEXITCODE -ne 0) { throw "WinSW n'a pas pu installer le service (code $LASTEXITCODE)." }
& $serviceExe start | Out-Host
if ($LASTEXITCODE -ne 0) { throw "WinSW n'a pas pu démarrer le service (code $LASTEXITCODE). Consulte $InstallDir\logs." }
Write-Ok "Service « $ServiceName » installé en démarrage automatique."

# --- 8. Pare-feu ----------------------------------------------------------------
# Le service tourne sous le compte système : Windows n'affichera jamais la
# fenêtre « Autoriser l'accès », il faut donc créer la règle nous-mêmes.
Write-Step "Ouverture du port $Port dans le pare-feu (réseaux privés)"
Get-NetFirewallRule -DisplayName $ServiceName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ServiceName -Direction Inbound -Protocol TCP `
    -LocalPort $Port -Action Allow -Profile Private, Domain | Out-Null
Write-Ok 'Règle créée.'

$publicNetworks = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
    Where-Object { $_.NetworkCategory -eq 'Public' }
foreach ($net in $publicNetworks) {
    Write-Warn "Le réseau « $($net.Name) » est classé Public : les autres appareils ne pourront pas se connecter."
    Write-Warn "Pour le passer en privé : Set-NetConnectionProfile -InterfaceIndex $($net.InterfaceIndex) -NetworkCategory Private"
}

# --- 9. Vérification ------------------------------------------------------------
Write-Step "Attente du démarrage de l'application (jusqu'à 60 s)"
$up = $false
for ($i = 0; $i -lt 30 -and -not $up; $i++) {
    Start-Sleep -Seconds 2
    try {
        Invoke-WebRequest -Uri "http://localhost:$Port/" -UseBasicParsing -TimeoutSec 2 | Out-Null
        $up = $true
    } catch [System.Net.WebException] {
        # Une réponse HTTP, même une erreur 404, prouve que le serveur tourne.
        if ($_.Exception.Response) { $up = $true }
    }
}

if ($up) {
    Write-Ok "L'application répond."
    Write-Host "`nAdresses d'accès :" -ForegroundColor Cyan
    Write-Host "    Sur ce PC : http://localhost:$Port"
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Write-Host "    Réseau local ($($_.InterfaceAlias)) : http://$($_.IPAddress):$Port" }
} else {
    Write-Warn "L'application ne répond pas encore. Consulte les journaux dans $InstallDir\logs."
}
Write-Host ''
