// Noms de decks et de cartes tels qu'affichés sur les téléphones (les données MTGJSON sont en anglais).

/** « Limit Break (FINAL FANTASY VII) » → « Limit Break » : la franchise entre parenthèses n'est pas utile une fois le deck choisi. */
export function displayDeckName(name) {
	return name.replace(/\s*\([^)]*\)\s*$/, '').trim() || name;
}

/** « Cloud, Ex-SOLDIER » → « Cloud » ; « Frodo, Adventurous Hobbit // … » → « Frodo ». */
export function shortCommanderName(name) {
	const front = name.split(' // ')[0];
	return front.split(',')[0].trim();
}

/** « Cloud », « Frodo & Sam » ; chaîne vide si aucun commandant n'est connu. */
export function commandersLabel(commanders) {
	return commanders.map(shortCommanderName).join(' & ');
}

/** « Limit Break (Cloud) » ; le nom seul si les commandants ne sont pas connus. */
export function deckLabel(deckName, commanders) {
	const label = displayDeckName(deckName);
	const names = commandersLabel(commanders);
	return names ? `${label} (${names})` : label;
}

/** Une carte réversible s'appelle « A // A » dans MTGJSON : on n'affiche qu'une fois le nom. */
export function cardLabel(name) {
	const halves = name.split(' // ');
	return halves.length === 2 && halves[0] === halves[1] ? halves[0] : name;
}

/** Minuscules sans accents ni ponctuation, pour comparer une recherche à un nom. */
export function normalize(text) {
	return text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}
