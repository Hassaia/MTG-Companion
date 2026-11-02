# MTG Companion

Compteur de points de vie pour Magic: The Gathering, en réseau local. Le serveur tourne sur un PC
Windows ; un grand écran affiche la partie et chaque joueur modifie ses PV depuis son téléphone,
connecté au même Wi-Fi. Aucun accès Internet n'est nécessaire, sauf pour afficher une carte
jamais vue (voir la section 5).

| Page | Pour qui |
|---|---|
| `/config` | Lancer ou réinitialiser une partie ; affiche l'adresse à ouvrir sur les téléphones |
| `/table` | Grand écran (PC, télé, tablette), lecture seule. Double-clic : plein écran |
| `/joueur` | Téléphone de chaque joueur |

## 1. Lancer le serveur

Prérequis : un JDK 25. `mvnw.cmd` a besoin de la variable `JAVA_HOME`. Pour la définir une fois
pour toutes (puis fermer et rouvrir les terminaux) :

```powershell
setx JAVA_HOME "C:\Users\wahib\.jdks\corretto-25.0.5"
```

Puis, dans un terminal ouvert dans le dossier du projet :

```powershell
.\mvnw.cmd spring-boot:run
```

Le serveur est prêt quand la ligne `Started MtgCompanionApplication` apparaît. `Ctrl+C` l'arrête.
On peut aussi le lancer depuis IntelliJ (flèche verte à côté de `main` dans `MtgCompanionApplication`).

La partie est sauvegardée dans `mtg-game-state.json`, dans le dossier de lancement, à chaque
modification. Elle est rechargée au démarrage suivant.

Lancer les tests : `.\mvnw.cmd test`

## 2. Autoriser le port 8080 dans le pare-feu Windows

Sans cela, les téléphones ne peuvent pas joindre le PC.

1. Le réseau Wi-Fi doit être en profil **Privé** : Paramètres > Réseau et Internet > Wi-Fi >
   (votre réseau) > Type de profil réseau > **Privé**.
2. Ouvrir le port, au choix :
   - Au premier lancement, Windows peut afficher « Autoriser l'accès » pour Java : cocher
     **Réseaux privés** et valider.
   - Ou, dans un PowerShell **lancé en administrateur** :

     ```powershell
     New-NetFirewallRule -DisplayName "MTG Companion (8080)" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
     ```

     Pour retirer la règle plus tard : `Remove-NetFirewallRule -DisplayName "MTG Companion (8080)"`

## 3. Trouver l'adresse du PC

Le plus simple : ouvrir `http://localhost:8080/config` sur le PC. L'adresse à saisir sur les
téléphones est affichée en haut, par exemple `http://192.168.0.47:8080/joueur`.

Sinon, dans un terminal : `ipconfig`, puis lire la ligne **Adresse IPv4** de la carte
« Carte réseau sans fil Wi-Fi » (ou « Ethernet » si le PC est branché par câble).

## 4. Se connecter depuis un téléphone

1. Connecter le téléphone **au même Wi-Fi** que le PC (pas en 4G/5G, ni sur un Wi-Fi invité).
2. Ouvrir le navigateur et saisir l'adresse affichée sur `/config`, par exemple
   `http://192.168.0.47:8080/joueur`. Bien écrire `http://` : certains navigateurs essaient
   sinon `https://`, qui ne fonctionne pas ici.
3. Choisir son joueur. Le choix est mémorisé sur le téléphone ; « Changer » permet d'en changer.

Si la page ne s'ouvre pas : vérifier le pare-feu et le profil réseau (étape 2), et que le PC
et le téléphone sont bien sur le même réseau.

Astuce : allonger la mise en veille automatique de l'écran du téléphone pendant la partie. Après
une veille, la page se reconnecte d'elle-même et récupère l'état à jour.

## 5. Montrer une carte sur la table

Sur `/joueur`, « Montrer une carte sur la table » ouvre une recherche par nom, en français ou en
anglais. La carte choisie s'affiche en plein écran sur `/table` (version française si elle
existe). N'importe quel téléphone peut la fermer ; elle se ferme seule après 30 secondes
(`mtg.card-display-duration` dans `application.properties`).

Champ de recherche vide, le téléphone propose les cartes qu'il a déjà envoyées : les favoris
(étoile ☆/★) en haut par ordre alphabétique, puis les 10 dernières cartes. Cette liste est
propre à chaque téléphone (mémorisée dans son navigateur).

Les données et images viennent de [Scryfall](https://scryfall.com). Seul le serveur les télécharge,
jamais les navigateurs : **le PC doit avoir accès à Internet** pour une carte jamais affichée.

Chaque carte affichée est gardée dans le dossier `card-cache/` (à côté de `mtg-game-state.json`) :
elle se réaffiche ensuite instantanément, même sans Internet. Sans connexion, la recherche ne
propose que ces cartes déjà vues. Le cache n'expire pas ; pour le vider, supprimer le dossier.

Si Scryfall ne répond pas, un message l'indique sur le téléphone ; la partie continue normalement.
