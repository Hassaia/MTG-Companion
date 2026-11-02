// Cartes déjà envoyées à la table depuis ce téléphone, et favoris, gardés dans le navigateur.
// Les favoris restent en haut et ne disparaissent jamais ; les autres cartes sont limitées aux plus récentes.

const STORAGE_KEY = 'mtg-companion.cardHistory';
export const MAX_RECENT = 10;

/**
 * @typedef {{ name: string, label: string, favorite: boolean, lastUsed: number }} HistoryEntry
 * name : nom anglais, envoyé au serveur ; label : nom affiché (français si possible)
 */

/** @returns {HistoryEntry[]} */
export function loadHistory() {
	try {
		const entries = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
		return Array.isArray(entries) ? entries.filter(isValidEntry) : [];
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
export function recordShown(entries, { name, label }, now = Date.now()) {
	const existing = entries.find((entry) => entry.name === name);
	const updated = existing
		? entries.map((entry) => (entry === existing ? { ...entry, label, lastUsed: now } : entry))
		: [...entries, { name, label, favorite: false, lastUsed: now }];
	return keepMostRecent(updated);
}

export function toggleFavorite(entries, name) {
	return keepMostRecent(entries.map((entry) =>
		(entry.name === name ? { ...entry, favorite: !entry.favorite } : entry)));
}

/** Favoris par ordre alphabétique (leur place ne bouge pas), puis les autres de la plus récente à la plus ancienne. */
export function orderForDisplay(entries) {
	const favorites = entries.filter((entry) => entry.favorite)
		.sort((a, b) => a.label.localeCompare(b.label, 'fr', { sensitivity: 'base' }));
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
	return entry && typeof entry.name === 'string' && typeof entry.label === 'string'
		&& typeof entry.favorite === 'boolean' && typeof entry.lastUsed === 'number';
}
