package com.wahib.mtg.mtg_companion.userimages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contenu du fichier {@code mtg-player-art.json}, distinct de la sauvegarde de la partie : les images
 * et les cadrages sont des préférences durables, qui survivent à une nouvelle partie.
 *
 * @param library bibliothèque partagée, par identifiant d'image
 * @param framing cadrages : numéro de joueur, puis identifiant d'image (téléversée, ou
 *                {@code <impression>-<face>} pour Scryfall), puis nombre de joueurs de la disposition (2, 3 ou 4)
 */
public record PlayerArtData(Map<String, UserImage> library, Map<Integer, Map<String, Map<Integer, Framing>>> framing) {

	public PlayerArtData {
		library = library == null ? new LinkedHashMap<>() : library;
		framing = framing == null ? new LinkedHashMap<>() : framing;
	}
}
