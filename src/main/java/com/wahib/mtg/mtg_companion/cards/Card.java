package com.wahib.mtg.mtg_companion.cards;

import java.util.List;

/**
 * Une carte telle qu'elle est affichée sur la table, et conservée dans le cache sur disque.
 *
 * @param name   nom anglais officiel (« Oracle »), qui identifie la carte de façon unique
 * @param lang   langue du texte affiché : {@code fr} si une version française existe, sinon {@code en}
 * @param faces  une entrée par face de texte (deux pour une carte double face ou une aventure)
 * @param images une image, ou deux pour une carte double face ; vide si Scryfall n'en a pas
 */
public record Card(String name, String lang, List<CardFace> faces, List<CardImage> images) {

	public Card {
		faces = List.copyOf(faces);
		images = List.copyOf(images);
	}

	public record CardFace(String name, String typeLine, String text) {
	}

	/**
	 * @param fileName  nom du fichier dans le cache, de la forme {@code <id scryfall>-<n° de face>.jpg}
	 * @param sourceUrl adresse de l'image chez Scryfall, utilisée uniquement pour la télécharger
	 */
	public record CardImage(String fileName, String sourceUrl) {
	}
}
