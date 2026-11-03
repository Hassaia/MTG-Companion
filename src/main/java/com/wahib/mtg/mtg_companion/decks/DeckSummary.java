package com.wahib.mtg.mtg_companion.decks;

import java.util.List;

/**
 * Un deck tel que proposé à la recherche.
 *
 * @param id         {@code fileName} MTGJSON (ex. {@code LimitBreakFinalFantasyVii_FIC}) ou identifiant d'un deck
 *                   importé ({@code custom_<uuid>}), stables
 * @param code       code d'édition MTGJSON (ex. {@code FIC}), null pour un deck importé
 * @param setName    nom de l'édition, ou null s'il n'est pas connu
 * @param commanders commandants connus (decks déjà téléchargés) ; vide sinon
 */
public record DeckSummary(String id, String name, String code, String setName, String releaseDate,
		List<String> commanders, DeckOrigin origin) {

	public DeckSummary {
		commanders = commanders == null ? List.of() : List.copyOf(commanders);
		origin = origin == null ? DeckOrigin.MTGJSON : origin;
	}

	/** Préconstruit MTGJSON. */
	public DeckSummary(String id, String name, String code, String setName, String releaseDate,
			List<String> commanders) {
		this(id, name, code, setName, releaseDate, commanders, DeckOrigin.MTGJSON);
	}
}
