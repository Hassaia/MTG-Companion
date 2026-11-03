package com.wahib.mtg.mtg_companion.decks;

/** D'où vient un deck : préconstruit officiel du catalogue MTGJSON, ou liste importée par un joueur. */
public enum DeckOrigin {
	MTGJSON,
	CUSTOM;

	/** Préfixe des identifiants des decks importés (les préconstruits MTGJSON n'en portent jamais). */
	static final String CUSTOM_ID_PREFIX = "custom_";

	static DeckOrigin of(String deckId) {
		return deckId != null && deckId.startsWith(CUSTOM_ID_PREFIX) ? CUSTOM : MTGJSON;
	}
}
