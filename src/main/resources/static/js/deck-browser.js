// Onglet « Decks en jeu » de la recherche de cartes : les decks choisis par les joueurs, puis les cartes
// du deck ouvert (commandants à part), avec une recherche rapide par nom. Choisir une carte la rend à
// l'appelant, qui l'envoie à la table par le chemin habituel de « Montrer une carte ».
//
// Les miniatures se chargent au fil du défilement, quelques-unes à la fois : le serveur les demande à
// Scryfall une par une, et elles sont ensuite gardées dans son cache.

import { cardLabel, deckLabel, normalize } from './deck-names.js';

const MAX_PARALLEL_THUMBNAILS = 3;
const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';

/**
 * @param {{ root: HTMLElement, onPick: (card: { name: string, label: string, printId: string | null }) => void }} options
 */
export function createDeckBrowser({ root, onPick }) {
	const deckList = root.querySelector('#deck-browser-decks');
	const deckView = root.querySelector('#deck-browser-view');
	const statusElement = root.querySelector('#deck-browser-status');
	const titleElement = root.querySelector('#deck-browser-title');
	const filterInput = root.querySelector('#deck-browser-filter');
	const commanderSection = root.querySelector('#deck-browser-commanders');
	const commanderList = commanderSection.querySelector('ul');
	const cardSection = root.querySelector('#deck-browser-cards');
	const cardList = cardSection.querySelector('ul');
	const cardTitle = cardSection.querySelector('h3');

	/** Lignes du deck ouvert : { item, key } où key sert au filtre. */
	let rows = [];
	let loadId = 0;
	const thumbnails = createThumbnailLoader();

	root.querySelector('#deck-browser-back').addEventListener('click', showDecks);
	filterInput.addEventListener('input', applyFilter);
	deckList.addEventListener('click', (event) => {
		const button = event.target.closest('button[data-deck]');
		if (button) {
			openDeck(button.dataset.deck, button.dataset.label);
		}
	});
	root.addEventListener('click', (event) => {
		const button = event.target.closest('button[data-card]');
		if (button) {
			onPick({
				name: button.dataset.card,
				label: button.dataset.label,
				printId: button.dataset.printId || null,
			});
		}
	});

	function setStatus(text, kind = '') {
		statusElement.textContent = text;
		statusElement.dataset.kind = kind;
	}

	/** Revient à la liste des decks et la recharge. @returns {Promise<number>} nombre de decks en jeu, -1 si inconnu */
	async function load() {
		showDecks();
		const id = ++loadId;
		setStatus('Chargement des decks…');
		try {
			const response = await fetch('/api/decks/in-play');
			if (!response.ok) {
				throw new Error(`HTTP ${response.status}`);
			}
			const decks = await response.json();
			if (id !== loadId) {
				return decks.length;
			}
			renderDecks(decks);
			return decks.length;
		} catch {
			if (id === loadId) {
				setStatus(NO_CONNECTION, 'error');
			}
			return -1;
		}
	}

	function renderDecks(decks) {
		deckList.replaceChildren(...decks.map((deck) => {
			const button = document.createElement('button');
			button.type = 'button';
			button.className = 'card-suggestion deck-choice';
			button.dataset.deck = deck.deckId;
			button.dataset.player = String(deck.playerId);
			const label = deckLabel(deck.deckName, deck.commanders);
			button.dataset.label = `${deck.playerName} — ${label}`;
			const player = document.createElement('span');
			player.className = 'suggestion-english';
			player.textContent = deck.playerName;
			const name = document.createElement('span');
			name.className = 'suggestion-label';
			name.textContent = label;
			button.append(player, name);
			const item = document.createElement('li');
			item.append(button);
			return item;
		}));
		setStatus(decks.length === 0
			? 'Aucun joueur n\'a encore choisi de deck. Utilisez la recherche globale, ou choisissez votre deck dans « Mon deck ».'
			: 'Touchez un deck pour voir ses cartes.');
	}

	function showDecks() {
		loadId++;
		thumbnails.reset();
		deckView.hidden = true;
		deckList.hidden = false;
		rows = [];
		filterInput.value = '';
	}

	async function openDeck(deckId, label) {
		const id = ++loadId;
		setStatus('Chargement du deck…');
		let deck;
		try {
			const response = await fetch(`/api/decks/${encodeURIComponent(deckId)}`);
			if (!response.ok) {
				const body = await response.json().catch(() => null);
				if (id === loadId) {
					setStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
				}
				return;
			}
			deck = await response.json();
		} catch {
			if (id === loadId) {
				setStatus(NO_CONNECTION, 'error');
			}
			return;
		}
		if (id !== loadId) {
			return;
		}
		thumbnails.reset();
		titleElement.textContent = label;
		deckList.hidden = true;
		deckView.hidden = false;
		filterInput.value = '';

		rows = [];
		commanderSection.querySelector('h3').textContent = deck.commanders.length > 1 ? 'Commandants' : 'Commandant';
		commanderList.replaceChildren(...deck.commanders.map((card) => cardRow(card)));
		cardList.replaceChildren(...deck.cards.map((card) => cardRow(card)));
		cardTitle.textContent = `Deck (${deck.cards.reduce((sum, card) => sum + card.count, 0)} cartes)`;
		applyFilter();
	}

	function cardRow(card) {
		const label = cardLabel(card.name);
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'deck-card';
		button.dataset.card = card.name;
		button.dataset.label = label;
		if (card.printId) {
			button.dataset.printId = card.printId;
		}

		const thumbnail = document.createElement('span');
		thumbnail.className = 'deck-card-thumbnail';
		if (card.printId) {
			thumbnails.watch(thumbnail, `/api/cards/thumbnails/${card.printId}.jpg`, label);
		}
		const name = document.createElement('span');
		name.className = 'deck-card-name';
		name.textContent = label;
		const count = document.createElement('span');
		count.className = 'deck-card-count';
		count.textContent = `×${card.count}`;
		button.append(thumbnail, name, count);

		const item = document.createElement('li');
		item.append(button);
		rows.push({ item, key: normalize(`${card.name} ${label}`) });
		return item;
	}

	function applyFilter() {
		const words = normalize(filterInput.value).split(' ').filter(Boolean);
		let shown = 0;
		for (const { item, key } of rows) {
			const match = words.every((word) => key.includes(word));
			item.hidden = !match;
			shown += match ? 1 : 0;
		}
		commanderSection.hidden = commanderList.querySelectorAll('li:not([hidden])').length === 0;
		cardSection.hidden = cardList.querySelectorAll('li:not([hidden])').length === 0;
		setStatus(shown === 0 ? 'Aucune carte de ce nom dans le deck.' : 'Touchez une carte pour l\'afficher sur la table.');
	}

	return { load, reset: showDecks, setStatus };
}

/**
 * Charge les miniatures des lignes visibles, {@link MAX_PARALLEL_THUMBNAILS} à la fois. Une ligne qui sort de
 * l'écran avant son tour n'est pas chargée ; une miniature indisponible garde son emplacement vide.
 */
function createThumbnailLoader() {
	const waiting = new Map();
	let running = 0;
	/** Change à chaque réinitialisation : une image encore en route d'un ancien deck est ignorée à son arrivée. */
	let generation = 0;
	let observer = null;

	function ensureObserver() {
		observer ??= new IntersectionObserver((entries) => {
			for (const entry of entries) {
				const job = waiting.get(entry.target);
				if (job) {
					job.visible = entry.isIntersecting;
				}
			}
			next();
		}, { rootMargin: '150px 0px' });
		return observer;
	}

	function next() {
		for (const [element, job] of waiting) {
			if (running >= MAX_PARALLEL_THUMBNAILS) {
				return;
			}
			if (!job.visible || job.started) {
				continue;
			}
			job.started = true;
			running++;
			load(element, job);
		}
	}

	function load(element, job) {
		const image = new Image();
		image.decoding = 'async';
		image.alt = '';
		const startedIn = generation;
		const done = (loaded) => {
			if (startedIn !== generation) {
				return;
			}
			running--;
			waiting.delete(element);
			observer?.unobserve(element);
			if (loaded) {
				element.replaceChildren(image);
				element.classList.add('loaded');
			}
			next();
		};
		image.onload = () => done(true);
		image.onerror = () => done(false);
		image.src = job.url;
	}

	return {
		watch(element, url, label) {
			element.setAttribute('role', 'img');
			element.setAttribute('aria-label', label);
			waiting.set(element, { url, visible: false, started: false });
			ensureObserver().observe(element);
		},
		reset() {
			observer?.disconnect();
			waiting.clear();
			running = 0;
			generation++;
		},
	};
}
