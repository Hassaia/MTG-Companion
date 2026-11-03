package com.wahib.mtg.mtg_companion.game;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Photographie immuable de l'état complet d'une partie : c'est ce qui est diffusé aux clients
 * et sauvegardé sur disque.
 */
public record GameSnapshot(Format format, int startingLife, List<PlayerSnapshot> players) {

	public GameSnapshot {
		players = List.copyOf(players);
	}

	/**
	 * @param eliminated calculé à partir des PV et des dégâts ; ignoré lors d'une restauration
	 * @param background illustration choisie pour la zone du joueur, ou null (fond par défaut)
	 * @param deckId identifiant MTGJSON du préconstruit choisi, ou null (absent d'une ancienne sauvegarde)
	 */
	public record PlayerSnapshot(int id, String name, int life, boolean eliminated,
			Map<Integer, Integer> commanderDamage, Background background, String deckId) {

		public PlayerSnapshot {
			// TreeMap : adversaires triés par numéro, pour un affichage stable
			commanderDamage = commanderDamage == null ? Map.of()
					: Collections.unmodifiableMap(new TreeMap<>(commanderDamage));
		}

		public PlayerSnapshot(int id, String name, int life, boolean eliminated, Map<Integer, Integer> commanderDamage,
				Background background) {
			this(id, name, life, eliminated, commanderDamage, background, null);
		}

		public PlayerSnapshot(int id, String name, int life, boolean eliminated, Map<Integer, Integer> commanderDamage) {
			this(id, name, life, eliminated, commanderDamage, null, null);
		}
	}

	/**
	 * Fond actif de la zone d'un joueur. L'image elle-même n'est jamais stockée ici : on retrouve une
	 * illustration Scryfall dans le cache par {@code printId} et {@code face}, une image envoyée par un
	 * joueur dans la bibliothèque par {@code imageId}.
	 * <p>
	 * Les anciennes sauvegardes n'ont ni {@code kind} ni {@code imageId} : c'est une illustration Scryfall,
	 * dont l'identifiant se déduit de l'impression et de la face.
	 *
	 * @param kind      {@code SCRYFALL} ou {@code UPLOAD} ; absent dans une ancienne sauvegarde
	 * @param imageId   identifiant de l'image : {@code <printId>-<face>} (Scryfall) ou identifiant de la bibliothèque
	 * @param cardName  nom anglais de la carte (Scryfall seulement)
	 * @param printId   identifiant Scryfall de l'impression (Scryfall seulement)
	 * @param face      numéro de la face illustrée (Scryfall seulement)
	 * @param artist    artiste tel que renseigné par Scryfall, ou null s'il ne l'est pas
	 * @param positionX position horizontale de l'image dans la zone pour la disposition en cours, de 0 (gauche) à 100 (droite)
	 * @param positionY position verticale, de 0 (haut) à 100 (bas)
	 */
	public record Background(String kind, String imageId, String cardName, String printId, int face, String artist,
			int positionX, int positionY) {

		public static final String SCRYFALL = "SCRYFALL";
		public static final String UPLOAD = "UPLOAD";

		private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
		private static final Pattern UUID_PATTERN = Pattern.compile(UUID);

		public Background {
			kind = kind == null ? SCRYFALL : kind;
			if (positionX < 0 || positionX > 100 || positionY < 0 || positionY > 100) {
				throw new InvalidActionException("Illustration de zone invalide");
			}
			switch (kind) {
				case SCRYFALL -> {
					if (cardName == null || cardName.isBlank() || printId == null || !UUID_PATTERN.matcher(printId).matches()
							|| face < 0 || face > 9) {
						throw new InvalidActionException("Illustration de zone invalide");
					}
					imageId = printId + "-" + face;
					artist = artist == null || artist.isBlank() ? null : artist;
				}
				case UPLOAD -> {
					if (imageId == null || !UUID_PATTERN.matcher(imageId).matches()) {
						throw new InvalidActionException("Image de zone invalide");
					}
					cardName = null;
					printId = null;
					face = 0;
					artist = null;
				}
				default -> throw new InvalidActionException("Type de fond inconnu");
			}
		}

		public static Background scryfall(String cardName, String printId, int face, String artist,
				int positionX, int positionY) {
			return new Background(SCRYFALL, null, cardName, printId, face, artist, positionX, positionY);
		}

		public static Background upload(String imageId, int positionX, int positionY) {
			return new Background(UPLOAD, imageId, null, null, 0, null, positionX, positionY);
		}

		public Background withPosition(int newPositionX, int newPositionY) {
			return new Background(kind, imageId, cardName, printId, face, artist, newPositionX, newPositionY);
		}
	}
}
