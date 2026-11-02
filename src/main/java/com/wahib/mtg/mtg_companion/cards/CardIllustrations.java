package com.wahib.mtg.mtg_companion.cards;

import java.util.List;

/**
 * Les illustrations distinctes d'une carte, chacune représentée par l'impression à afficher :
 * la française si elle existe, sinon l'anglaise. Conservée dans le cache sur disque.
 *
 * @param name nom anglais officiel de la carte
 */
public record CardIllustrations(String name, List<Illustration> illustrations) {

	public CardIllustrations {
		illustrations = List.copyOf(illustrations);
	}

	/**
	 * @param printId         identifiant Scryfall de l'impression : c'est lui que retiennent les favoris
	 * @param thumbnailUrl    petite image chez Scryfall, téléchargée à la demande pour le téléphone
	 * @param images          grandes images pour la table (deux pour une carte double face)
	 * @param arts            illustrations seules (art_crop), une par face illustrée ; {@code null} pour une
	 *                        entrée de cache écrite avant leur prise en charge, à récupérer de nouveau
	 */
	public record Illustration(String printId, String lang, String setName, String collectorNumber,
			String releasedAt, String thumbnailUrl, List<Card.CardImage> images, List<Art> arts) {

		public Illustration {
			images = List.copyOf(images);
			arts = arts == null ? null : List.copyOf(arts);
		}

		/** Impression sans information d'illustration seule : voir {@code arts}. */
		public Illustration(String printId, String lang, String setName, String collectorNumber,
				String releasedAt, String thumbnailUrl, List<Card.CardImage> images) {
			this(printId, lang, setName, collectorNumber, releasedAt, thumbnailUrl, images, null);
		}

		public boolean hasArt() {
			return arts != null && !arts.isEmpty();
		}
	}

	/**
	 * Illustration d'une face, sans le cadre de la carte (image {@code art_crop} de Scryfall).
	 *
	 * @param face      numéro de la face (0 pour une carte à une seule image)
	 * @param faceName  nom de la face, pour l'afficher au choix
	 * @param artist    artiste tel que renseigné par Scryfall ; absent s'il ne l'est pas
	 * @param sourceUrl adresse chez Scryfall, utilisée uniquement pour la télécharger dans le cache
	 */
	public record Art(int face, String faceName, String artist, String sourceUrl) {
	}
}
