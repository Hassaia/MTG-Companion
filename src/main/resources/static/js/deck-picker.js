// Section « Mon deck » : choix du préconstruit officiel du joueur. Le choix est enregistré dans la partie
// par le serveur (message SET_PLAYER_DECK) : tous les appareils le voient, et il survit au rechargement.
// Le nom et les commandants viennent du serveur, qui les connaît par l'identifiant du deck.

import { deckLabel } from './deck-names.js';
import { initDeckImport, openDeckImport } from './deck-import.js';

const DEBOUNCE_MS = 300;
const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';
const HINT = 'Tapez un nom de deck, une édition ou un commandant : Final Fantasy, Limit Break…';
const CUSTOM_HINT = 'Decks importés par les joueurs de la table. Tapez un nom ou un commandant.';

const section = document.getElementById('my-deck');
const nameElement = document.getElementById('my-deck-name');
const clearButton = document.getElementById('my-deck-clear');
const panel = document.getElementById('deck-picker');
const input = document.getElementById('deck-picker-input');
const statusElement = document.getElementById('deck-picker-status');
const results = document.getElementById('deck-picker-results');

let callbacks = null;
let debounceTimer = null;
let pendingSearch = null;
/** Identifiant du deck dont le nom est affiché, pour ne redemander le nom que lorsque le choix change. */
let shownDeckId;
let updateId = 0;
/** Catégorie affichée : préconstruits officiels (MTGJSON) ou decks importés (CUSTOM). */
let origin = 'MTGJSON';

/**
 * @param {{ getMe: () => object | null, send: (message: object) => void }} options
 */
export function initDeckPicker(options) {
	callbacks = options;
	initDeckImport({ onSaved: () => search() });
	panel.querySelectorAll('[data-origin]').forEach((tab) => tab.addEventListener('click', () => selectOrigin(tab.dataset.origin)));
	document.getElementById('my-deck-change').addEventListener('click', open);
	document.getElementById('close-deck-picker').addEventListener('click', close);
	clearButton.addEventListener('click', () => {
		const me = callbacks.getMe();
		if (me) {
			callbacks.send({ type: 'CLEAR_PLAYER_DECK', player: me.id });
		}
	});
	input.addEventListener('input', () => {
		clearTimeout(debounceTimer);
		debounceTimer = setTimeout(search, DEBOUNCE_MS);
	});
	panel.querySelector('form').addEventListener('submit', (event) => {
		event.preventDefault();
		clearTimeout(debounceTimer);
		search();
	});
	results.addEventListener('click', (event) => {
		const action = event.target.closest('button[data-action]');
		if (action) {
			manageDeck(action.dataset.action, action.dataset.id, action.dataset.name);
			return;
		}
		const button = event.target.closest('button[data-deck]');
		const me = callbacks.getMe();
		if (button && me) {
			callbacks.send({ type: 'SET_PLAYER_DECK', player: me.id, deckId: button.dataset.deck });
			close();
		}
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden && document.getElementById('deck-import').hidden) {
			close();
		}
	});
}

/** À appeler à chaque état reçu : affiche le deck du joueur, en le demandant au serveur s'il a changé. */
export async function updateMyDeck(me) {
	const deckId = me.deckId ?? null;
	clearButton.hidden = deckId === null;
	if (deckId === shownDeckId) {
		return;
	}
	shownDeckId = deckId;
	section.classList.toggle('has-deck', deckId !== null);
	if (deckId === null) {
		nameElement.textContent = 'Aucun deck choisi';
		return;
	}
	nameElement.textContent = 'Chargement…';
	const id = ++updateId;
	try {
		const response = await fetch('/api/decks/in-play');
		if (!response.ok) {
			throw new Error(`HTTP ${response.status}`);
		}
		const mine = (await response.json()).find((deck) => deck.playerId === me.id);
		if (id === updateId) {
			nameElement.textContent = mine ? deckLabel(mine.deckName, mine.commanders) : 'Deck choisi';
		}
	} catch {
		if (id === updateId) {
			// Le choix est bien enregistré ; seul son nom n'a pas pu être lu
			nameElement.textContent = 'Deck choisi';
			shownDeckId = undefined;
		}
	}
}

function open() {
	panel.hidden = false;
	document.body.classList.add('modal-open');
	input.value = '';
	results.replaceChildren();
	selectOrigin(origin);
	input.focus();
}

function close() {
	panel.hidden = true;
	document.body.classList.remove('modal-open');
	clearTimeout(debounceTimer);
	pendingSearch?.abort();
}

function selectOrigin(next) {
	origin = next;
	panel.querySelectorAll('[data-origin]').forEach((tab) => tab.setAttribute('aria-selected', String(tab.dataset.origin === origin)));
	showStatus(origin === 'CUSTOM' ? CUSTOM_HINT : HINT);
	search();
}

/** Renommer, remplacer la liste ou supprimer un deck importé (la suppression demande confirmation). */
async function manageDeck(action, id, name) {
	try {
		if (action === 'replace') {
			openDeckImport({ id, name });
		} else if (action === 'rename') {
			const newName = prompt('Nouveau nom du deck :', name)?.trim();
			if (newName && newName !== name) {
				const response = await fetch(`/api/decks/custom/${encodeURIComponent(id)}`, {
					method: 'PATCH',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ name: newName }),
				});
				if (!response.ok) {
					const body = await response.json().catch(() => null);
					showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
					return;
				}
				search();
			}
		} else if (action === 'delete') {
			const inPlay = await fetch('/api/decks/in-play').then((response) => (response.ok ? response.json() : []));
			const users = inPlay.filter((deck) => deck.deckId === id).map((deck) => deck.playerName);
			const warning = users.length > 0 ? `

Utilisé par : ${users.join(', ')}. Ils n'auront plus de deck.` : '';
			if (confirm(`Supprimer définitivement le deck « ${name} » ?${warning}`)) {
				const response = await fetch(`/api/decks/custom/${encodeURIComponent(id)}`, { method: 'DELETE' });
				if (!response.ok && response.status !== 404) {
					showStatus(`Suppression impossible (HTTP ${response.status}).`, 'error');
					return;
				}
				search();
			}
		}
	} catch {
		showStatus(NO_CONNECTION, 'error');
	}
}

function showStatus(text, kind = '') {
	statusElement.textContent = text;
	statusElement.dataset.kind = kind;
}

async function search() {
	pendingSearch?.abort();
	const request = new AbortController();
	pendingSearch = request;
	const query = input.value.trim();
	showStatus('Recherche…');
	try {
		const response = await fetch(`/api/decks?origin=${origin}&q=${encodeURIComponent(query)}`, { signal: request.signal });
		if (!response.ok) {
			const body = await response.json().catch(() => null);
			showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			results.replaceChildren();
			return;
		}
		render(await response.json(), query);
	} catch (error) {
		if (error.name !== 'AbortError') {
			showStatus(NO_CONNECTION, 'error');
		}
	}
}

function render(decks, query) {
	results.replaceChildren(...decks.map((deck) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'card-suggestion';
		button.dataset.deck = deck.id;
		const name = document.createElement('span');
		name.className = 'suggestion-label';
		name.textContent = deck.name;
		const details = document.createElement('span');
		details.className = 'suggestion-english';
		details.textContent = [deck.setName ?? deck.code, deck.releaseDate?.slice(0, 4),
			deck.commanders.length > 0 ? deck.commanders.join(' & ') : null].filter(Boolean).join(' · ');
		button.append(name, details);
		const item = document.createElement('li');
		item.append(button);
		if (deck.origin === 'CUSTOM') {
			item.append(manageButtons(deck));
		}
		return item;
	}));
	const empty = origin === 'CUSTOM' ? 'Aucun deck importé. Utilisez « Importer un deck ».' : 'Aucun préconstruit trouvé.';
	showStatus(decks.length === 0 ? empty : query || origin === 'CUSTOM' ? '' : 'Les plus récents d\'abord.');
}

function manageButtons(deck) {
	const actions = document.createElement('div');
	actions.className = 'deck-manage';
	for (const [action, label] of [['rename', 'Renommer'], ['replace', 'Remplacer la liste'], ['delete', 'Supprimer']]) {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'my-deck-button';
		button.dataset.action = action;
		button.dataset.id = deck.id;
		button.dataset.name = deck.name;
		button.textContent = label;
		actions.append(button);
	}
	return actions;
}
