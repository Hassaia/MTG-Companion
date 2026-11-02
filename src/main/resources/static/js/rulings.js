// Consultation des rulings Scryfall d'une carte, sur ce téléphone uniquement : rien n'est envoyé à la table
// ni aux autres joueurs. La carte est choisie avec la recherche existante (mode « choisir une carte »).

import { openCardSearch } from './card-search.js';

const SOURCES = { wotc: 'Wizards of the Coast', scryfall: 'Scryfall' };
const NO_CONNECTION = 'Pas de connexion au serveur.';

const panel = document.getElementById('rulings-view');
const cardName = document.getElementById('rulings-card-name');
const thumbnail = document.getElementById('rulings-thumbnail');
const status = document.getElementById('rulings-status');
const list = document.getElementById('rulings-list');

/** Consultation en cours : une nouvelle carte annule la précédente. */
let pending = null;

export function initRulings() {
	document.getElementById('open-rulings').addEventListener('click', search);
	document.getElementById('close-rulings').addEventListener('click', close);
	document.getElementById('rulings-new-search').addEventListener('click', () => {
		close();
		search();
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
}

function search() {
	// Toute impression convient : les rulings ne dépendent pas de l'illustration
	openCardSearch('Rulings', show, null, { artOnly: false });
}

function close() {
	pending?.controller.abort();
	pending = null;
	panel.hidden = true;
	document.body.classList.remove('modal-open');
}

function show(card) {
	if (!card.printId) {
		return;
	}
	// Déjà en cours pour cette impression (ex. double validation) : une seule requête
	if (pending?.printId === card.printId && !panel.hidden) {
		return;
	}
	pending?.controller.abort();
	const controller = new AbortController();
	pending = { printId: card.printId, controller };

	document.getElementById('rulings-title').textContent = 'Rulings';
	cardName.textContent = card.label;
	thumbnail.hidden = true;
	thumbnail.onload = () => { thumbnail.hidden = false; };
	thumbnail.onerror = () => { thumbnail.hidden = true; };
	thumbnail.src = `/api/cards/thumbnails/${encodeURIComponent(card.printId)}.jpg`;
	list.replaceChildren();
	showStatus('Chargement des rulings…');
	panel.hidden = false;
	document.body.classList.add('modal-open');
	load(card.printId, controller);
}

async function load(printId, controller) {
	try {
		const response = await fetch(`/api/cards/rulings?printId=${encodeURIComponent(printId)}`,
			{ signal: controller.signal });
		if (!response.ok) {
			const body = await response.json().catch(() => null);
			showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		render(await response.json());
	} catch (error) {
		// Réponse obsolète : le joueur a déjà changé de carte ou fermé la vue
		if (error.name !== 'AbortError') {
			showStatus(NO_CONNECTION, 'error');
		}
	} finally {
		if (pending?.controller === controller) {
			pending = null;
		}
	}
}

function render({ rulings, stale }) {
	if (rulings.length === 0) {
		showStatus('Aucun ruling disponible pour cette carte.');
		return;
	}
	showStatus(stale ? 'Scryfall est injoignable : rulings enregistrés, peut-être incomplets.' : '',
		stale ? 'warning' : 'info');
	list.replaceChildren(...rulings.map((ruling) => {
		const text = document.createElement('p');
		text.textContent = ruling.comment;
		const meta = document.createElement('span');
		meta.className = 'ruling-meta';
		meta.textContent = [formatDate(ruling.publishedAt), SOURCES[ruling.source] ?? ruling.source]
			.filter(Boolean).join(' · ');
		const item = document.createElement('li');
		item.className = 'ruling';
		item.append(text, meta);
		return item;
	}));
}

function formatDate(date) {
	if (!date) {
		return null;
	}
	const parsed = new Date(`${date}T00:00:00`);
	return Number.isNaN(parsed.getTime()) ? date : parsed.toLocaleDateString('fr-FR');
}

function showStatus(text, kind = 'info') {
	status.textContent = text;
	status.dataset.kind = kind;
}
