# MTG Companion en service Windows

Ce dossier permet d'installer MTG Companion comme **service Windows**. Le service démarre avec le PC, **avant toute ouverture de session**. Il suffit donc d'appuyer sur le bouton d'alimentation : au bout d'une minute environ, l'application est accessible depuis les téléphones et les autres appareils du réseau local, sans que personne ait besoin du mot de passe Windows.

Le service repose sur [WinSW](https://github.com/winsw/winsw) (v2.12.0), un petit programme libre qui fait tourner n'importe quel exécutable, ici `java -jar`, comme un service Windows. Le script d'installation le télécharge automatiquement.

## Contenu du dossier

| Fichier | Rôle |
|---|---|
| `installer-service.bat` | Point d'entrée : double-clic pour installer ou mettre à jour |
| `desinstaller-service.bat` | Point d'entrée : double-clic pour désinstaller |
| `install-service.ps1` | Script d'installation (appelé par le `.bat`) |
| `uninstall-service.ps1` | Script de désinstallation (appelé par le `.bat`) |
| `mtg-companion.xml.template` | Modèle de configuration WinSW, complété par le script d'installation |

## Prérequis

- Windows 10 ou 11, 64 bits.
- Un JDK 25 installé. Le script cherche `java.exe` dans `%JAVA_HOME%\bin`, puis dans le `PATH`.
- Le jar de l'application, construit depuis la racine du dépôt :
  ```
  mvnw package -DskipTests
  ```
  (ou `gradlew bootJar` si le projet utilise Gradle). Le script prend le jar le plus récent de `target\` ou `build\libs\`.
- Un accès à GitHub pour télécharger WinSW. Sinon, télécharge [`WinSW-x64.exe`](https://github.com/winsw/winsw/releases/tag/v2.12.0) à la main et place-le dans ce dossier : le script l'utilisera à la place.

## Installation

1. Construis le jar (voir ci-dessus).
2. Double-clique sur `installer-service.bat` et accepte la demande d'autorisation administrateur.
3. À la fin, le script affiche les adresses à ouvrir, par exemple :
   ```
   Sur ce PC : http://localhost:8080
   Réseau local (Wi-Fi) : http://192.168.1.42:8080
   ```

C'est l'adresse « Réseau local » qu'il faut ouvrir sur les téléphones et sur le grand écran.

### Ce que fait le script

1. Il se relance en administrateur si nécessaire.
2. Il trouve le jar et `java.exe`.
3. Il récupère WinSW.
4. Si le service existe déjà, il l'arrête et le supprime.
5. Il copie dans `C:\MTG-Companion\` :
   - le jar, renommé `mtg-companion.jar` ;
   - WinSW, renommé `mtg-companion.exe` ;
   - la configuration `mtg-companion.xml`, générée à partir du modèle avec le chemin de Java et le port.
6. Il installe le service en **démarrage automatique** et le démarre.
7. Il crée une règle de pare-feu entrante pour le port, sur les réseaux privés et de domaine.
8. Il attend que l'application réponde, puis affiche les adresses d'accès.

### Options

Les options se passent après le nom du `.bat` (ou du `.ps1`) :

| Option | Par défaut | Exemple |
|---|---|---|
| `-Port` | `8080` | `installer-service.bat -Port 9000` |
| `-InstallDir` | `C:\MTG-Companion` | `installer-service.bat -InstallDir D:\Apps\MTG` |
| `-JarPath` | jar le plus récent de `target\` ou `build\libs\` | `installer-service.bat -JarPath D:\mtg.jar` |
| `-JavaPath` | `%JAVA_HOME%\bin\java.exe`, puis le `PATH` | `installer-service.bat -JavaPath "C:\Program Files\Java\jdk-25\bin\java.exe"` |

Si tu changes `-InstallDir` à l'installation, indique-le aussi à la désinstallation.

## Mettre à jour l'application

Reconstruis le jar, puis relance `installer-service.bat`. Le script remplace l'ancien service par le nouveau. Les fichiers créés par l'application dans `C:\MTG-Companion\` ne sont pas touchés.

## Gérer le service au quotidien

Dans un terminal administrateur :

```
C:\MTG-Companion\mtg-companion.exe status     :: Started / Stopped / NonExistent
C:\MTG-Companion\mtg-companion.exe stop
C:\MTG-Companion\mtg-companion.exe start
C:\MTG-Companion\mtg-companion.exe restart
```

Le service apparaît aussi dans la console **Services** (`services.msc`) sous le nom « MTG Companion ».

Si l'application plante, WinSW la relance automatiquement après 10 secondes.

## Journaux

Les journaux se trouvent dans `C:\MTG-Companion\logs\` :

- `mtg-companion.out.log` : sortie standard de Spring Boot (les journaux habituels de l'application) ;
- `mtg-companion.err.log` : sortie d'erreur ;
- `mtg-companion.wrapper.log` : journal de WinSW (démarrages, arrêts, relances).

Au-delà de 10 Mo, un nouveau fichier est créé. Les 5 plus récents sont conservés.

## Désinstallation

Double-clique sur `desinstaller-service.bat`. Le service est arrêté et supprimé, et la règle de pare-feu est retirée. Le dossier `C:\MTG-Companion\` est conservé. Pour le supprimer aussi :

```
desinstaller-service.bat -RemoveFiles
```

## Points d'attention

### L'écran du PC reste sur l'écran de connexion

Le service fait tourner le **serveur**, pas un navigateur. Tant que personne n'ouvre de session, l'écran du PC affiche l'écran de connexion Windows. L'application doit donc être ouverte depuis un autre appareil : téléphone, tablette, télé avec navigateur, autre ordinateur…

Pour afficher l'application sur l'écran du PC lui-même sans saisir de mot de passe, il faut en plus :

1. une **ouverture de session automatique**, par exemple avec [Autologon](https://learn.microsoft.com/sysinternals/downloads/autologon) de Sysinternals ;
2. un navigateur en mode kiosque lancé à l'ouverture de session : un raccourci vers `msedge.exe --kiosk http://localhost:8080 --edge-kiosk-type=fullscreen` dans le dossier `shell:startup`.

Attention : dans ce cas, ta session Windows est ouverte pour quiconque allume le PC.

### Le réseau doit être disponible avant l'ouverture de session

- **Câble Ethernet** : aucun problème.
- **Wi-Fi** : le réseau doit être enregistré avec l'option « Se connecter automatiquement ». Windows s'y connecte alors dès l'écran de connexion. Pour le vérifier, redémarre le PC sans ouvrir de session, puis essaie d'ouvrir l'application depuis un téléphone.

### Le réseau doit être classé « Privé »

La règle de pare-feu ne s'applique qu'aux réseaux privés. Si Windows a classé ton réseau domestique comme « Public », les autres appareils ne pourront pas se connecter. Le script le signale. Pour corriger : **Paramètres > Réseau et Internet > (ton réseau) > Type de profil réseau > Privé**.

### L'adresse IP du PC peut changer

La box attribue l'adresse IP et peut en donner une autre après un redémarrage. Pour garder toujours la même adresse, configure une **réservation DHCP** pour le PC dans l'interface de ta box.

### Le service tourne sous le compte système

Le service s'exécute sous le compte `LocalSystem`, pas sous ton compte utilisateur. En pratique :

- le dossier courant de l'application est `C:\MTG-Companion\` : c'est là que sont créés les fichiers écrits avec un chemin relatif ;
- `user.home` correspond au profil système (`C:\Windows\System32\config\systemprofile`), pas à ton dossier personnel. Évite donc de stocker des données dans `~`.

### Démarrage rapide de Windows

Avec le « démarrage rapide » activé (option par défaut), un appui sur le bouton d'alimentation après un arrêt sort le système d'hibernation. Les services automatiques sont bien relancés, rien à faire de particulier. En cas de comportement étrange, un **redémarrage** (et non un arrêt suivi d'un allumage) repart d'un système entièrement neuf.

## Dépannage

| Symptôme | Piste |
|---|---|
| Le script indique « Aucun jar trouvé » | Construis le projet, ou passe `-JarPath`. |
| Le service démarre puis s'arrête | Regarde `logs\mtg-companion.err.log` : mauvaise version de Java, port déjà utilisé, etc. |
| `http://localhost:8080` fonctionne sur le PC mais pas depuis le téléphone | Vérifie que le réseau est classé Privé et que le téléphone est sur le même réseau. Vérifie aussi la règle « MTG Companion » dans le pare-feu (`wf.msc`). |
| Rien ne répond après un démarrage sans session | Sûrement le Wi-Fi : vérifie l'option « Se connecter automatiquement », ou utilise un câble. |
| « L'ancien service n'a pas pu être supprimé » | Ferme la console Services (`services.msc`) et relance le script. |
