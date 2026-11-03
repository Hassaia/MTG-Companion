package com.wahib.mtg.mtg_companion.decks;

import java.util.List;

/**
 * Un préconstruit du catalogue, tel que proposé à la recherche.
 *
 * @param id         {@code fileName} MTGJSON, stable (ex. {@code LimitBreakFinalFantasyVii_FIC})
 * @param code       code d'édition MTGJSON (ex. {@code FIC})
 * @param setName    nom de l'édition, ou null s'il n'est pas connu
 * @param commanders commandants connus (decks déjà téléchargés) ; vide sinon
 */
public record DeckSummary(String id, String name, String code, String setName, String releaseDate,
		List<String> commanders) {

	public DeckSummary {
		commanders = commanders == null ? List.of() : List.copyOf(commanders);
	}
}
