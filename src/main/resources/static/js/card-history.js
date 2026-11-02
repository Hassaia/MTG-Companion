// Cartes déjà envoyées à la table depuis ce téléphone, et favoris, gardés dans le navigateur.
// Chaque entrée retient l'illustration choisie : un favori réaffiche toujours la même image.
// Les favoris restent en haut et ne disparaissent jamais ; les autres cartes sont limitées aux plus récentes.

const STORAGE_KEY = 'mtg-companion.cardHistory';
export const MAX_RECENT = 10;

/**
 * @typedef {object} HistoryEntry
 * @property {string} name          nom anglais, envoyé au serveur
 * @property {string} label         nom affiché (français si possible)
 * @property {string|null} printId  impression choisie (identifiant Scryfall), ou null pour le choix automatique
 * @property {string|null} printLabel édition et numéro, par exemple « Magic 2010 · n° 146 »
 * @property {boolean} favorite
 * @property {number} lastUsed
 */

/** Une même carte avec deux illustrations différentes donne deux entrées distinctes. */
export function entryKey({ name, printId }) {
	return `${name}\u0000${printId ?? ''}`;
}

/** @returns {HistoryEntry[]} */
export function loadHistory() {
	try {
		const entries = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
		return Array.isArray(entries)
			? entries.filter(isValidEntry).map((entry) => ({ printId: null, printLabel: null, ...entry }))
			: [];
	} catch {
		// localStorage indisponible (navigation privée) ou contenu illisible : on repart de zéro
		return [];
	}
}

/** @param {HistoryEntry[]} entries */
export function saveHistory(entries) {
	try {
		localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
	} catch {
		// L'historique restera valable jusqu'au rechargement de la page
	}
}

/** Carte envoyée à la table : ajoutée, ou remontée en tête des cartes récentes. */
export function recordShown(entries, { name, label, printId = null, printLabel = null }, now = Date.now()) {
	const key = entryKey({ name, printId });
	const existing = entries.find((entry) => entryKey(entry) === key);
	const updated = existing
		? entries.map((entry) => (entry === existing ? { ...entry, label, printLabel, lastUsed: now } : entry))
		: [...entries, { name, label, printId, printLabel, favorite: false, lastUsed: now }];
	return keepMostRecent(updated);
}

export function toggleFavorite(entries, key) {
	return keepMostRecent(entries.map((entry) =>
		(entryKey(entry) === key ? { ...entry, favorite: !entry.favorite } : entry)));
}

/** Favoris par ordre alphabétique (leur place ne bouge pas), puis les autres de la plus récente à la plus ancienne. */
export function orderForDisplay(entries) {
	const favorites = entries.filter((entry) => entry.favorite)
		.sort((a, b) => a.label.localeCompare(b.label, 'fr', { sensitivity: 'base' })
			|| (a.printLabel ?? '').localeCompare(b.printLabel ?? '', 'fr'));
	const recent = entries.filter((entry) => !entry.favorite)
		.sort((a, b) => b.lastUsed - a.lastUsed);
	return [...favorites, ...recent];
}

function keepMostRecent(entries) {
	const recentToKeep = new Set(entries.filter((entry) => !entry.favorite)
		.sort((a, b) => b.lastUsed - a.lastUsed)
		.slice(0, MAX_RECENT));
	return entries.filter((entry) => entry.favorite || recentToKeep.has(entry));
}

function isValidEntry(entry) {
	const optionalString = (value) => value === undefined || value === null || typeof value === 'string';
	return entry && typeof entry.name === 'string' && typeof entry.label === 'string'
		&& typeof entry.favorite === 'boolean' && typeof entry.lastUsed === 'number'
		&& optionalString(entry.printId) && optionalString(entry.printLabel);
}
