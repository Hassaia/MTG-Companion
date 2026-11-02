package com.wahib.mtg.mtg_companion.userimages;

/**
 * Une image de la bibliothèque partagée : seules ces métadonnées sont enregistrées dans le fichier JSON,
 * le fichier lui-même est dans le dossier des images, sous le nom {@code <id>.<extension>}.
 *
 * @param id          identifiant généré par le serveur (UUID en minuscules)
 * @param name        nom d'origine, nettoyé, pour l'affichage seulement
 * @param contentType {@code image/jpeg}, {@code image/png} ou {@code image/webp}
 * @param extension   extension du fichier stocké, sans point
 * @param uploadedAt  date d'import, au format ISO 8601
 */
public record UserImage(String id, String name, String contentType, String extension, int width, int height,
		long bytes, String uploadedAt) {
}
