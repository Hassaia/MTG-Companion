// Recherche d'une carte Magic depuis le téléphone et envoi à la table.
// Les requêtes passent par notre serveur, qui seul interroge Scryfall.
//
// Trois vues sous le champ de recherche :
//  - champ vide : favoris et cartes récentes de ce téléphone, chacun avec son illustration mémorisée ;
//  - en cours de frappe : suggestions ;
//  - suggestion choisie, si la carte a plusieurs illustrations : grille pour choisir laquelle afficher.
//
// Pour l'envoi à la table, un second onglet « Decks en jeu » (deck-browser.js) propose les cartes des decks
// choisis par les joueurs ; la carte choisie part par le même chemin (même langue, même historique).

// La même recherche sert à choisir l'illustration du fond de sa zone (openCardSearch avec un rappel) :
// la carte choisie est alors rendue à l'appelant au lieu d'être envoyée à la table.

import { entryKey, loadHistory, orderForDisplay, recordShown, saveHistory, toggleFavorite } from './card-history.js';
import { createDeckBrowser } from './deck-browser.js';

const DEBOUNCE_MS = 350;
const MIN_QUERY_LENGTH = 2;
const HINT = 'Tapez le nom d\'une carte (français ou anglais), ou son édition et son numéro : FIC 168.';
const SEARCH_TITLE = 'Montrer une carte';
const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';

const panel = document.getElementById('card-search');
const input = document.getElementById('card-search-input');
const statusElement = document.getElementById('card-search-status');
const suggestionList = document.getElementById('card-suggestions');
const historySection = document.getElementById('card-history');
const historyList = document.getElementById('card-history-list');
const illustrationSection = document.getElementById('card-illustrations');
const illustrationGrid = document.getElementById('card-illustration-grid');

const tabs = document.getElementById('card-tabs');
const globalSearch = document.getElementById('global-search');
const deckBrowserElement = document.getElementById('deck-browser');
const languageSelector = document.getElementById('card-language');
const LANGUAGE_KEY = 'mtg-card-language';
const LANGUAGES = ['fr', 'en', 'both'];

let history = loadHistory();
let language = loadLanguage();
let debounceTimer = null;
let pendingSuggestions = null;
let busy = false;
let onCardSent = () => {};
let onClose = () => {};
/** Rappel du mode « choisir une carte sans l'envoyer à la table », ou null. */
let pickHandler = null;
/** Rappel quand la recherche est refermée sans avoir choisi de carte (mode « choisir une carte »). */
let pickCancel = null;
/** En mode « choisir une carte » : vrai si seules les impressions avec illustration seule sont proposées (fond de zone). */
let artOnly = true;
let deckBrowser = null;
let activeTab = 'global';
/** Vrai dès que l'utilisateur a touché un onglet : le choix automatique de l'onglet ne doit plus le déplacer. */
let tabTouched = false;

/**
 * @param {{ onCardSent: (label: string, warnings: string[]) => void, onClose?: () => void }} callbacks
 *        onClose : recherche d'envoi à la table refermée (après envoi réussi ou annulation)
 */
export function initCardSearch(callbacks) {
	onCardSent = callbacks.onCardSent;
	onClose = callbacks.onClose ?? onClose;
	document.getElementById('open-card-search').addEventListener('click', () => open());
	document.getElementById('close-card-search').addEventListener('click', close);
	languageSelector.addEventListener('click', (event) => {
		const button = event.target.closest('button[data-lang]');
		if (button) {
			language = button.dataset.lang;
			saveLanguage(language);
			renderLanguage();
		}
	});
	renderLanguage();

	deckBrowser = createDeckBrowser({ root: deckBrowserElement, onPick: (card) => sendAndRemember(card) });
	tabs.addEventListener('click', (event) => {
		const tab = event.target.closest('button[data-tab]');
		if (tab) {
			tabTouched = true;
			selectTab(tab.dataset.tab);
		}
	});

	input.addEventListener('input', () => {
		clearTimeout(debounceTimer);
		illustrationSection.hidden = true;
		if (input.value.trim().length < MIN_QUERY_LENGTH) {
			// Champ vidé : retour immédiat à l'historique, sans attendre
			pendingSuggestions?.abort();
			showHistory();
			return;
		}
		debounceTimer = setTimeout(loadSuggestions, DEBOUNCE_MS);
	});
	// Touche « Rechercher » du clavier : pas d'attente
	panel.querySelector('form').addEventListener('submit', (event) => {
		event.preventDefault();
		clearTimeout(debounceTimer);
		illustrationSection.hidden = true;
		loadSuggestions();
	});

	// Un seul gestionnaire pour les trois vues
	panel.addEventListener('click', (event) => {
		const favoriteButton = event.target.closest('button[data-favorite]');
		if (favoriteButton) {
			history = toggleFavorite(history, favoriteButton.dataset.favorite);
			saveHistory(history);
			renderHistory();
			return;
		}
		const cardButton = event.target.closest('button[data-name]');
		if (!cardButton) {
			return;
		}
		const card = {
			name: cardButton.dataset.name,
			label: cardButton.dataset.label,
			printId: cardButton.dataset.printId ?? null,
			printLabel: cardButton.dataset.printLabel ?? null,
		};
		// Une suggestion par nom laisse choisir l'illustration ; une recherche par édition et numéro
		// (« FIC 168 »), l'historique et la grille désignent déjà une impression précise
		// (sans exigence d'illustration, l'historique sans impression mémorisée passe aussi par ce choix)
		const needsPrint = cardButton.dataset.source === 'suggestion'
			|| (pickHandler && !artOnly && cardButton.dataset.source === 'history');
		if (needsPrint && !card.printId) {
			chooseIllustration(card);
		} else {
			sendAndRemember(card);
		}
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
}

/**
 * Ouvre la recherche pour choisir une carte sans l'envoyer à la table : seules les impressions qui
 * ont une illustration seule sont proposées.
 *
 * @param {string} title
 * @param {(card: { name: string, label: string, printId: string | null }) => void} onPick
 * @param {() => void} [onCancel] appelé si la recherche est refermée sans choix
 * @param {{ artOnly?: boolean }} [options] {@code artOnly: false} : toute impression convient (pas seulement
 *        celles qui ont une illustration seule) et le choix renvoie toujours un {@code printId}
 */
export function openCardSearch(title, onPick, onCancel = null, options = {}) {
	artOnly = options.artOnly ?? true;
	open(title, onPick, onCancel);
}

/** Ouvre la recherche d'envoi à la table (mode intégré sans écran de jeu). */
export function openSendSearch() {
	open();
}

/** @returns {Promise<boolean>} false si le serveur est injoignable */
export async function hideCard() {
	try {
		const response = await fetch('/api/cards/hide', { method: 'POST' });
		return response.ok;
	} catch {
		return false;
	}
}

function open(title = SEARCH_TITLE, onPick = null, onCancel = null) {
	if (onPick === null) {
		artOnly = true;
	}
	pickHandler = onPick;
	pickCancel = onCancel;
	document.getElementById('card-search-title').textContent = title;
	// La langue ne concerne que l'affichage sur la table, pas le choix d'un fond de zone
	languageSelector.hidden = onPick !== null;
	panel.hidden = false;
	document.body.classList.add('modal-open');
	input.value = '';
	// Un autre onglet a pu modifier l'historique entre-temps
	history = loadHistory();
	activeTab = 'global';
	showHistory();
	tabTouched = false;
	// Les onglets ne concernent que l'envoi à la table (pas le choix d'un fond de zone ni les rulings)
	tabs.hidden = onPick !== null;
	if (onPick === null) {
		selectTab('decks');
		chooseStartTab();
	} else {
		selectTab('global');
	}
}

/** Premier onglet : les decks en jeu s'il y en a au moins un, sinon la recherche globale. */
async function chooseStartTab() {
	const count = await deckBrowser.load();
	if (!tabTouched && !panel.hidden) {
		selectTab(count > 0 ? 'decks' : 'global');
	}
}

function selectTab(tab) {
	activeTab = tab;
	tabs.querySelectorAll('button[data-tab]').forEach((button) => {
		button.setAttribute('aria-selected', String(button.dataset.tab === tab));
	});
	deckBrowserElement.hidden = tab !== 'decks';
	globalSearch.hidden = tab !== 'global';
	if (tab === 'global') {
		input.focus();
	} else {
		input.blur();
		if (tabTouched) {
			deckBrowser.load();
		}
	}
}

function close() {
	deckBrowser.reset();
	panel.hidden = true;
	document.body.classList.remove('modal-open');
	clearTimeout(debounceTimer);
	pendingSuggestions?.abort();
	if (pickCancel) {
		const cancel = pickCancel;
		pickCancel = null;
		cancel();
	} else if (!pickHandler) {
		onClose();
	}
}

// --- Favoris et cartes récentes

function showHistory() {
	suggestionList.replaceChildren();
	illustrationSection.hidden = true;
	showStatus(HINT);
	renderHistory();
}

function renderHistory() {
	const entries = orderForDisplay(history);
	historySection.hidden = entries.length === 0;
	historyList.replaceChildren(...entries.map((entry) => {
		const item = document.createElement('li');
		item.className = 'history-item';

		const favoriteButton = document.createElement('button');
		favoriteButton.type = 'button';
		favoriteButton.className = 'favorite-toggle';
		favoriteButton.dataset.favorite = entryKey(entry);
		favoriteButton.setAttribute('aria-pressed', String(entry.favorite));
		favoriteButton.setAttribute('aria-label',
			entry.favorite ? `Retirer « ${entry.label} » des favoris` : `Ajouter « ${entry.label} » aux favoris`);
		favoriteButton.textContent = entry.favorite ? '★' : '☆';

		item.append(cardButton(entry, 'history'), favoriteButton);
		return item;
	}));
}

// --- Suggestions

async function loadSuggestions() {
	const query = input.value.trim();
	pendingSuggestions?.abort();
	if (query.length < MIN_QUERY_LENGTH) {
		showHistory();
		return;
	}
	historySection.hidden = true;
	// Une frappe plus récente annule la recherche en cours : seule la dernière réponse s'affiche
	const request = new AbortController();
	pendingSuggestions = request;
	showStatus('Recherche…');
	try {
		const response = await fetch(`/api/cards/suggestions?q=${encodeURIComponent(query)}`, { signal: request.signal });
		if (!response.ok) {
			throw new Error(`HTTP ${response.status}`);
		}
		renderSuggestions(await response.json());
	} catch (error) {
		if (error.name !== 'AbortError') {
			showStatus(NO_CONNECTION, 'error');
		}
	}
}

function renderSuggestions({ suggestions, warning }) {
	suggestionList.replaceChildren(...suggestions.map((suggestion) => {
		const item = document.createElement('li');
		item.append(cardButton(suggestion, 'suggestion'));
		return item;
	}));

	if (warning) {
		showStatus(warning, 'warning');
	} else if (suggestions.length === 0) {
		showStatus('Aucune carte trouvée.');
	} else {
		showStatus('');
	}
}

// --- Choix de l'illustration

async function chooseIllustration(card) {
	if (busy) {
		return;
	}
	busy = true;
	panel.classList.add('sending');
	// La première fois, le serveur doit interroger Scryfall : cela peut prendre une ou deux secondes
	showStatus(`Recherche des illustrations de « ${card.label} »…`);
	let illustrations;
	try {
		const response = await fetch(`/api/cards/illustrations?name=${encodeURIComponent(card.name)}`);
		if (!response.ok) {
			const body = await response.json().catch(() => null);
			showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		illustrations = (await response.json()).illustrations;
		if (pickHandler && artOnly) {
			illustrations = illustrations.filter((illustration) => illustration.hasArt);
		}
	} catch {
		showStatus(NO_CONNECTION, 'error');
		return;
	} finally {
		busy = false;
		panel.classList.remove('sending');
	}

	if (pickHandler && illustrations.length === 0) {
		showStatus(artOnly
			? `Aucune illustration utilisable pour « ${card.label} ».`
			: `Aucune impression trouvée pour « ${card.label} ».`, 'warning');
		return;
	}
	if (illustrations.length <= 1) {
		// Une seule illustration (ou aucune connue) : pas de choix à faire
		const only = illustrations[0];
		sendAndRemember({ ...card, printId: only?.printId ?? null, printLabel: only ? printLabel(only) : null });
		return;
	}
	renderIllustrations(card, illustrations);
}

function renderIllustrations(card, illustrations) {
	suggestionList.replaceChildren();
	historySection.hidden = true;
	showStatus(`${illustrations.length} illustrations : touchez celle à afficher.`);
	document.getElementById('card-illustrations-title').textContent = `Choisir l'illustration : ${card.label}`;

	illustrationGrid.replaceChildren(...illustrations.map((illustration) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'illustration-choice';
		button.dataset.source = 'illustration';
		button.dataset.name = card.name;
		button.dataset.label = card.label;
		button.dataset.printId = illustration.printId;
		const label = printLabel(illustration);
		if (label) {
			button.dataset.printLabel = label;
		}

		if (illustration.thumbnail) {
			const image = document.createElement('img');
			// Les vignettes sont téléchargées par le serveur au fil du défilement, pas toutes d'un coup
			image.loading = 'lazy';
			image.decoding = 'async';
			image.src = illustration.thumbnail;
			image.alt = `${card.label}, ${label ?? 'illustration'}`;
			button.append(image);
		}
		const caption = document.createElement('span');
		caption.className = 'illustration-caption';
		caption.textContent = illustration.setName ?? 'Illustration';
		const details = document.createElement('span');
		details.className = 'illustration-details';
		details.textContent = [illustration.collectorNumber && `n° ${illustration.collectorNumber}`,
			illustration.lang?.toUpperCase()].filter(Boolean).join(' · ');
		button.append(caption, details);

		const item = document.createElement('li');
		item.append(button);
		return item;
	}));
	illustrationSection.hidden = false;
	// Le clavier masque souvent la grille : on le referme
	input.blur();
}

function printLabel({ setName, collectorNumber }) {
	if (!setName) {
		return null;
	}
	return collectorNumber ? `${setName} · n° ${collectorNumber}` : setName;
}

// --- Langue d'affichage (préférence de ce téléphone ; localStorage peut être indisponible)

function loadLanguage() {
	try {
		const stored = localStorage.getItem(LANGUAGE_KEY);
		return LANGUAGES.includes(stored) ? stored : 'fr';
	} catch {
		return 'fr';
	}
}

function saveLanguage(value) {
	try {
		localStorage.setItem(LANGUAGE_KEY, value);
	} catch {
		// Non mémorisé : le choix reste valable jusqu'au rechargement de la page
	}
}

function renderLanguage() {
	languageSelector.querySelectorAll('button[data-lang]').forEach((button) => {
		button.setAttribute('aria-checked', String(button.dataset.lang === language));
	});
}

// --- Envoi à la table

async function sendAndRemember(card) {
	if (pickHandler) {
		const handler = pickHandler;
		pickCancel = null;
		close();
		handler(card);
		return;
	}
	const sent = await sendCard(card);
	if (sent) {
		history = recordShown(history, card);
		saveHistory(history);
		close();
		onCardSent(card.label, sent.warnings);
	}
}

async function sendCard({ name, label, printId }) {
	if (busy) {
		return false;
	}
	busy = true;
	panel.classList.add('sending');
	showStatus(`Envoi de « ${label} » à la table…`);
	try {
		const response = await fetch('/api/cards/show', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(printId ? { name, printId, lang: language } : { name, lang: language }),
		});
		if (response.ok) {
			const body = await response.json().catch(() => null);
			return { warnings: body?.warnings ?? [] };
		}
		const body = await response.json().catch(() => null);
		showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
		return false;
	} catch {
		showStatus(NO_CONNECTION, 'error');
		return false;
	} finally {
		busy = false;
		panel.classList.remove('sending');
	}
}

/**
 * Bouton d'une carte : nom français en gros ; en petit, le nom anglais s'il diffère et
 * l'édition de l'illustration mémorisée.
 */
function cardButton({ name, label, printId, printLabel: edition }, source) {
	const button = document.createElement('button');
	button.type = 'button';
	button.className = 'card-suggestion';
	button.dataset.source = source;
	button.dataset.name = name;
	button.dataset.label = label;
	if (printId) {
		button.dataset.printId = printId;
	}
	if (edition) {
		button.dataset.printLabel = edition;
	}
	const labelElement = document.createElement('span');
	labelElement.className = 'suggestion-label';
	labelElement.textContent = label;
	button.append(labelElement);

	const secondary = [label !== name ? name : null, edition].filter(Boolean).join(' · ');
	if (secondary) {
		const secondaryElement = document.createElement('span');
		secondaryElement.className = 'suggestion-english';
		secondaryElement.textContent = secondary;
		button.append(secondaryElement);
	}
	return button;
}

function showStatus(text, kind = 'info') {
	statusElement.textContent = text;
	statusElement.dataset.kind = kind;
	if (activeTab === 'decks') {
		// L'envoi d'une carte d'un deck signale ses erreurs là où l'utilisateur regarde
		deckBrowser.setStatus(text, kind);
	}
}
