package com.wahib.mtg.mtg_companion.decks;

import java.util.List;

/**
 * Cartes d'un deck, préconstruit officiel ou deck importé. Les jetons ne sont pas conservés.
 *
 * @param origin     {@link DeckOrigin#MTGJSON} si absent (anciens fichiers du cache)
 * @param code       code d'édition du préconstruit, null pour un deck importé
 * @param releaseDate date de sortie du préconstruit, ou date de dernière mise à jour d'un deck importé
 * @param commanders commandants (un ou plusieurs), à part du reste du deck
 * @param cards      les autres cartes, triées par nom, sans doublon d'impression
 */
public record DeckContent(String id, String name, String code, String releaseDate, List<DeckCard> commanders,
		List<DeckCard> cards, DeckOrigin origin) {

	/**
	 * @param name    nom anglais tel que dans MTGJSON (« Face A // Face B » pour une carte à deux faces)
	 * @param printId identifiant Scryfall de l'impression exacte, ou null s'il manque dans MTGJSON
	 * @param setCode code d'édition (minuscules) pour le repli par édition et numéro
	 */
	public record DeckCard(String name, int count, String printId, String setCode, String number) {
	}

	public DeckContent {
		commanders = List.copyOf(commanders);
		cards = List.copyOf(cards);
		origin = origin == null ? DeckOrigin.MTGJSON : origin;
	}

	/** Préconstruit MTGJSON. */
	public DeckContent(String id, String name, String code, String releaseDate, List<DeckCard> commanders,
			List<DeckCard> cards) {
		this(id, name, code, releaseDate, commanders, cards, DeckOrigin.MTGJSON);
	}
}
