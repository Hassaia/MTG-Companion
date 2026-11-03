package com.wahib.mtg.mtg_companion.decks;

import java.util.List;

/**
 * Cartes d'un préconstruit. Les jetons ne sont pas conservés.
 *
 * @param commanders commandants (un ou plusieurs), à part du reste du deck
 * @param cards      les autres cartes, triées par nom, sans doublon d'impression
 */
public record DeckContent(String id, String name, String code, String releaseDate, List<DeckCard> commanders,
		List<DeckCard> cards) {

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
	}
}
