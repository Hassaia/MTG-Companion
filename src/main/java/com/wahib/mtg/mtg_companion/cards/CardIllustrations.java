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
	 */
	public record Illustration(String printId, String lang, String setName, String collectorNumber,
			String releasedAt, String thumbnailUrl, List<Card.CardImage> images) {

		public Illustration {
			images = List.copyOf(images);
		}
	}
}
