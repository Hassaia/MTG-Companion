// Fond de la zone d'un joueur : adresse de l'image et crédit.
// Une illustration Scryfall est servie depuis le cache du serveur, une image importée depuis la bibliothèque.

/** @param {{ kind?: string, imageId?: string, printId?: string, face?: number }} background */
export function artUrl(background) {
	if (background.kind === 'UPLOAD') {
		return `/api/user-images/${background.imageId}`;
	}
	return `/api/cards/arts/${background.printId}-${background.face}.jpg`;
}

/**
 * Crédit exigé par Scryfall pour ses illustrations : l'artiste tel que Scryfall le renseigne (jamais
 * deviné), Scryfall pour l'image et le copyright de Wizards of the Coast. Une image importée n'en a pas.
 *
 * @param {{ kind?: string, artist?: string | null }} background
 */
export function artCreditText(background) {
	if (background.kind === 'UPLOAD') {
		return 'Image personnalisée';
	}
	return `Illustration : ${background.artist || 'artiste non renseigné'} · Image : Scryfall · Magic: The Gathering © Wizards of the Coast`;
}
