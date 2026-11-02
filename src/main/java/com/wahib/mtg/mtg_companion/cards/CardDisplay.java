package com.wahib.mtg.mtg_companion.cards;

import java.util.List;

/**
 * Ce qu'il faut afficher sur la table pour une demande « Montrer une carte » : une version (FR ou EN),
 * ou deux côte à côte, toutes issues de la même édition et du même numéro de collection.
 *
 * @param name     nom anglais officiel de la carte
 * @param warnings explications à montrer quand une langue demandée n'a pas pu être affichée
 */
public record CardDisplay(String name, List<Variant> variants, List<String> warnings) {

	public CardDisplay {
		variants = List.copyOf(variants);
		warnings = List.copyOf(warnings);
	}

	/** @param images une image, ou deux pour une carte double face */
	public record Variant(String lang, List<Card.CardImage> images) {
	}
}
