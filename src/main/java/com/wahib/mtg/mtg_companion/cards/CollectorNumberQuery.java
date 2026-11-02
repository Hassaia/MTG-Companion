package com.wahib.mtg.mtg_companion.cards;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recherche d'une impression précise par son code d'édition et son numéro de collection, tels
 * qu'imprimés en bas de la carte : « FIC 168 », « fic #168 », « M10-146 ».
 *
 * @param setCode code d'édition en minuscules, comme l'attend Scryfall
 */
public record CollectorNumberQuery(String setCode, String collectorNumber) {

	/**
	 * Code d'édition : 2 à 6 lettres ou chiffres, dont au moins une lettre. Séparateur obligatoire
	 * (espace, # ou tiret) pour ne pas confondre avec un nom. Numéro : chiffres, éventuellement
	 * suivis d'une lettre de variante (« 123a »).
	 */
	private static final Pattern PATTERN =
			Pattern.compile("^(?=[0-9]*[a-z])([a-z0-9]{2,6})\\s*(?:#|-|\\s)\\s*([0-9]{1,4}[a-z]?)$");

	public static Optional<CollectorNumberQuery> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		Matcher matcher = PATTERN.matcher(text.strip().toLowerCase(Locale.ROOT));
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return Optional.of(new CollectorNumberQuery(matcher.group(1), matcher.group(2)));
	}
}
