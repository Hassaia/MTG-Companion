// Recherche d'une carte Magic depuis le téléphone et envoi à la table.
// Les requêtes passent par notre serveur, qui seul interroge Scryfall.
// Champ vide : la liste des favoris et des cartes récentes de ce téléphone est proposée à la place.

import { loadHistory, orderForDisplay, recordShown, saveHistory, toggleFavorite } from './card-history.js';

const DEBOUNCE_MS = 350;
const MIN_QUERY_LENGTH = 2;
const HINT = 'Tapez le nom d\'une carte, en français ou en anglais.';
const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';

const panel = document.getElementById('card-search');
const input = document.getElementById('card-search-input');
const statusElement = document.getElementById('card-search-status');
const suggestionList = document.getElementById('card-suggestions');
const historySection = document.getElementById('card-history');
const historyList = document.getElementById('card-history-list');

let history = loadHistory();
let debounceTimer = null;
let pendingSuggestions = null;
let sending = false;

/** @param {{ onCardSent: (label: string) => void }} callbacks */
export function initCardSearch({ onCardSent }) {
	document.getElementById('open-card-search').addEventListener('click', open);
	document.getElementById('close-card-search').addEventListener('click', close);

	input.addEventListener('input', () => {
		clearTimeout(debounceTimer);
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
		loadSuggestions();
	});

	// Un même gestionnaire pour les suggestions et l'historique
	panel.addEventListener('click', async (event) => {
		const favoriteButton = event.target.closest('button[data-favorite]');
		if (favoriteButton) {
			history = toggleFavorite(history, favoriteButton.dataset.favorite);
			saveHistory(history);
			renderHistory();
			return;
		}
		const cardButton = event.target.closest('button[data-name]');
		if (cardButton && await sendCard(cardButton.dataset.name, cardButton.dataset.label)) {
			history = recordShown(history, { name: cardButton.dataset.name, label: cardButton.dataset.label });
			saveHistory(history);
			close();
			onCardSent(cardButton.dataset.label);
		}
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
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

function open() {
	panel.hidden = false;
	document.body.classList.add('modal-open');
	input.value = '';
	// Un autre onglet a pu modifier l'historique entre-temps
	history = loadHistory();
	showHistory();
	input.focus();
}

function close() {
	panel.hidden = true;
	document.body.classList.remove('modal-open');
	clearTimeout(debounceTimer);
	pendingSuggestions?.abort();
}

function showHistory() {
	suggestionList.replaceChildren();
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
		favoriteButton.dataset.favorite = entry.name;
		favoriteButton.setAttribute('aria-pressed', String(entry.favorite));
		favoriteButton.setAttribute('aria-label',
			entry.favorite ? `Retirer « ${entry.label} » des favoris` : `Ajouter « ${entry.label} » aux favoris`);
		favoriteButton.textContent = entry.favorite ? '★' : '☆';

		item.append(cardButton(entry), favoriteButton);
		return item;
	}));
}

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
		item.append(cardButton(suggestion));
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

/** Bouton d'une carte : nom français en gros, nom anglais en petit s'il est différent. */
function cardButton({ name, label }) {
	const button = document.createElement('button');
	button.type = 'button';
	button.className = 'card-suggestion';
	button.dataset.name = name;
	button.dataset.label = label;
	const labelElement = document.createElement('span');
	labelElement.className = 'suggestion-label';
	labelElement.textContent = label;
	button.append(labelElement);
	if (label !== name) {
		const englishName = document.createElement('span');
		englishName.className = 'suggestion-english';
		englishName.textContent = name;
		button.append(englishName);
	}
	return button;
}

async function sendCard(name, label) {
	if (sending) {
		return false;
	}
	sending = true;
	panel.classList.add('sending');
	// La première fois, le serveur doit récupérer la carte et ses images : cela peut prendre une ou deux secondes
	showStatus(`Envoi de « ${label} » à la table…`);
	try {
		const response = await fetch('/api/cards/show', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ name }),
		});
		if (response.ok) {
			return true;
		}
		const body = await response.json().catch(() => null);
		showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
		return false;
	} catch {
		showStatus(NO_CONNECTION, 'error');
		return false;
	} finally {
		sending = false;
		panel.classList.remove('sending');
	}
}

function showStatus(text, kind = 'info') {
	statusElement.textContent = text;
	statusElement.dataset.kind = kind;
}
