# MTG Companion

Compteur de PV pour Magic: The Gathering en réseau local (serveur sur PC, table = grand écran, joueurs = téléphones) + affichage de cartes via Scryfall. Détails d'usage : `README.md` (ne pas le relire sans besoin).

## Stack
- Backend : Java 25, Spring Boot 4.1 (webmvc + websocket), Maven (`mvnw.cmd`).
- Frontend : HTML / CSS / JS vanilla, sans build ni framework (`src/main/resources/static/`).
- API externe : Scryfall (`api.scryfall.com`), cache disque dans `card-cache/`.
- Persistance : fichier JSON `mtg-game-state.json` (pas de base de données).

## Carte du code (`src/main/java/com/wahib/mtg/mtg_companion/`)
- `game/` : modèle de la partie (`Game`, `Player`, `GameSettings`, `Format`, `GameSnapshot`).
- `server/` : WebSocket (`GameWebSocketHandler`, `GameBroadcaster`, `ClientMessage`/`ServerMessage`), `GameService`, `GameStateStore`, `PageRoutes`.
- `cards/` : Scryfall (`ScryfallClient`, `ScryfallCardMapper`), `CardService`, `CardCache`, `CardController`, `CardDisplayService`, `RequestThrottle`, `CollectorNumberQuery`.
- `storage/AtomicFiles` : écriture atomique des fichiers.
- Front : `static/{config,table,joueur}.html` + `js/` + `css/` du même nom ; `connection.js` = WebSocket partagé.
- Tests : `src/test/java/...` en miroir des packages ; fixtures Scryfall dans `src/test/resources/scryfall/`, `FakeScryfallServer` simule l'API (jamais d'appel réseau réel dans les tests).

## Règles
- Respecter l'architecture existante ; suivre le style des fichiers voisins.
- Ne pas refactorer les fichiers non concernés.
- Ne pas ajouter de dépendances sans demande.
- Éviter les explications longues : réponses courtes, pas de récapitulatif de ce qui est visible dans le diff.
- Ne lancer que les tests pertinents (voir ci-dessous), jamais la suite complète sans raison.
- Code, commentaires, messages d'UI et commits en français (comme l'existant).

## Commandes (PowerShell, depuis la racine)
- Un test : `.\mvnw.cmd -q test -Dtest=NomDuTest`
- Une méthode : `.\mvnw.cmd -q test -Dtest=NomDuTest#methode`
- Suite complète (uniquement avant PR / gros changement) : `.\mvnw.cmd -q test`
- Lancer l'app : `.\mvnw.cmd spring-boot:run` (port 8080). Nécessite `JAVA_HOME` (JDK 25).

## Économie de tokens
- Utiliser Grep/Glob ciblés plutôt que lire des fichiers entiers ; lire avec `offset`/`limit`.
- Ne jamais lire : `target/`, `card-cache/`, `mtg-game-state.json`, `.idea/`, `HELP.md`, les gros JSON de `src/test/resources/scryfall/` (sauf si le sujet est la fixture).
- Pour un changement front, ne toucher que la page concernée (`table`, `joueur` ou `config`) et ses `js/css`.
- Pour un bug, lire d'abord le test existant du composant concerné.

## Git
- Branche par fonctionnalité (`feature/...`), PR vers `main`. Ne pas committer sans demande.
- Ne jamais committer `mtg-game-state.json` ni `card-cache/` (déjà dans `.gitignore`).
