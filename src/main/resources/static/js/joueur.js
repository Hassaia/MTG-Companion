import { hideCard, initCardSearch, openSendSearch } from './card-search.js';
import { initRulings } from './rulings.js';
import { connectToGame } from './connection.js';
import { LifeChangeIndicator } from './life-change.js';
import { initZoneBackground } from './zone-background.js';

/**
 * Les dégâts de commandant sont affichés « à l'envers » : chaque adversaire part d'une réserve de
 * 21 PV de commandant qui descend à chaque dégât. Le serveur, lui, compte les dégâts reçus.
 */
const COMMANDER_POOL = 21;
const STORAGE_KEY = 'mtg-companion.playerId';
const TOAST_DURATION_MS = 2500;

const topbar = document.getElementById('topbar');
const title = document.getElementById('title');
const changePlayerButton = document.getElementById('change-player');
const chooseScreen = document.getElementById('choose-screen');
const playScreen = document.getElementById('play-screen');
const commanderRows = document.getElementById('commander-rows');

let currentGame = null;
/**
 * Mode intégré (iframe de /table) : le joueur vient de l'URL ; le localStorage n'est ni lu ni écrit pour
 * l'identité. Il n'y a ni choix ni changement de joueur.
 */
const params = new URLSearchParams(location.search);
/** Variante « cartes » : seule la recherche de carte est affichée, sans joueur ni connexion WebSocket. */
const CARDS_ONLY = params.get('embedded') === 'cards';
const EMBEDDED = CARDS_ONLY || params.get('embedded') === 'true';
if (EMBEDDED) {
	document.body.classList.add('embedded');
}
if (CARDS_ONLY) {
	document.body.classList.add('cards-only');
}

let selectedPlayerId = EMBEDDED ? readEmbeddedPlayerId() : readStoredPlayerId();
/** Composition affichée sur l'écran de jeu : les lignes ne sont recréées que si elle change. */
let renderedLayout = null;
let commanderRowElements = new Map();
let ownLifeChange = null;

// En mode cartes, /table affiche déjà la carte envoyée par HTTP : aucune connexion n'est nécessaire
const connection = CARDS_ONLY ? null : connectToGame({
	onState(message) {
		currentGame = message.game;
		render();
	},
	onError: showToast,
	// Après une coupure, le serveur renvoie la carte affichée s'il y en a encore une
	onOpen: hideCardBanner,
	onCardShown(message) {
		document.getElementById('card-banner-name').textContent = message.card.name;
		document.getElementById('card-banner').hidden = false;
	},
	onCardHidden: hideCardBanner,
});

function hideCardBanner() {
	document.getElementById('card-banner').hidden = true;
}

// --- Mémorisation du joueur (localStorage peut être indisponible, en navigation privée par exemple)

function readEmbeddedPlayerId() {
	const value = Number(params.get('playerId'));
	return Number.isInteger(value) && value > 0 ? value : null;
}

function readStoredPlayerId() {
	try {
		const value = Number(localStorage.getItem(STORAGE_KEY));
		return Number.isInteger(value) && value > 0 ? value : null;
	} catch {
		return null;
	}
}

function storePlayerId(playerId) {
	if (EMBEDDED) {
		return;
	}
	try {
		if (playerId === null) {
			localStorage.removeItem(STORAGE_KEY);
		} else {
			localStorage.setItem(STORAGE_KEY, String(playerId));
		}
	} catch {
		// Le choix sera simplement à refaire au prochain chargement
	}
}

function selectPlayer(playerId) {
	selectedPlayerId = playerId;
	storePlayerId(playerId);
	render();
}

// --- Affichage

function render() {
	if (!currentGame) {
		return;
	}
	const me = currentGame.players.find((player) => player.id === selectedPlayerId);
	if (me) {
		renderPlayScreen(me);
	} else if (EMBEDDED) {
		// Joueur disparu : /table ferme la modale, on n'affiche surtout pas le choix du joueur
		chooseScreen.hidden = true;
		playScreen.hidden = true;
	} else {
		// Joueur jamais choisi, ou disparu (nouvelle partie avec moins de joueurs)
		renderChooseScreen();
	}
}

function renderChooseScreen() {
	renderedLayout = null;
	chooseScreen.hidden = false;
	playScreen.hidden = true;
	changePlayerButton.hidden = true;
	delete topbar.dataset.player;
	title.textContent = 'Choisir son joueur';

	const choices = document.getElementById('player-choices');
	choices.replaceChildren(...currentGame.players.map((player) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'player-choice';
		button.dataset.player = String(player.id);
		const name = document.createElement('span');
		name.textContent = player.name;
		const life = document.createElement('span');
		life.className = 'choice-life';
		life.textContent = `${player.life} PV`;
		button.append(name, life);
		button.addEventListener('click', () => selectPlayer(player.id));

		const item = document.createElement('li');
		item.append(button);
		return item;
	}));
}

function renderPlayScreen(me) {
	chooseScreen.hidden = true;
	playScreen.hidden = false;
	changePlayerButton.hidden = EMBEDDED;
	topbar.dataset.player = String(me.id);
	title.textContent = me.name;

	const layout = JSON.stringify([me.id, currentGame.format, currentGame.players.map((player) => player.name)]);
	if (layout !== renderedLayout) {
		buildCommanderRows(me);
		// Nouveau joueur ou nouvelle partie : la variation repart de zéro
		ownLifeChange?.hide();
		ownLifeChange = new LifeChangeIndicator(document.querySelector('#own-life .life-change'));
		renderedLayout = layout;
	}

	renderOpponents(me);

	const ownLife = document.getElementById('own-life');
	ownLife.classList.toggle('eliminated', me.eliminated);
	document.getElementById('own-life-value').textContent = String(me.life);
	ownLifeChange.update(me.life);

	for (const [opponentId, { row, amount }] of commanderRowElements) {
		const remaining = COMMANDER_POOL - (me.commanderDamage[opponentId] ?? 0);
		amount.textContent = String(remaining);
		row.classList.toggle('lethal', remaining <= 0);
	}
}

function renderOpponents(me) {
	const opponents = currentGame.players.filter((player) => player.id !== me.id);
	document.getElementById('opponents').replaceChildren(...opponents.map((opponent) => {
		const item = document.createElement('li');
		item.dataset.player = String(opponent.id);
		item.classList.toggle('eliminated', opponent.eliminated);
		const name = document.createElement('span');
		name.className = 'opponent-name';
		name.textContent = opponent.name;
		const life = document.createElement('span');
		life.className = 'opponent-life';
		life.textContent = String(opponent.life);
		item.append(name, life);
		return item;
	}));
}

function buildCommanderRows(me) {
	const opponentIds = Object.keys(me.commanderDamage).map(Number);
	document.getElementById('commander-section').hidden = opponentIds.length === 0;
	commanderRowElements = new Map();

	commanderRows.replaceChildren(...opponentIds.map((opponentId) => {
		const opponent = currentGame.players.find((player) => player.id === opponentId);
		const row = document.createElement('li');
		row.className = 'commander-row';
		row.dataset.player = String(opponentId);

		const from = document.createElement('span');
		from.className = 'from';
		from.textContent = opponent.name;
		const amount = document.createElement('span');
		amount.className = 'amount';
		row.append(
			from,
			commanderPoolButton(opponentId, -1, `Retirer 1 PV de commandant face à ${opponent.name}`),
			amount,
			commanderPoolButton(opponentId, 1, `Rendre 1 PV de commandant face à ${opponent.name}`));

		commanderRowElements.set(opponentId, { row, amount });
		return row;
	}));
}

/** @param poolDelta variation de la réserve affichée : -1 sur la réserve = +1 dégât reçu */
function commanderPoolButton(opponentId, poolDelta, label) {
	const button = document.createElement('button');
	button.type = 'button';
	button.dataset.from = String(opponentId);
	button.dataset.damageDelta = String(-poolDelta);
	button.setAttribute('aria-label', label);
	button.textContent = poolDelta > 0 ? '+1' : '−1';
	return button;
}

let toastTimer = null;

function showToast(text, kind = 'error') {
	const toast = document.getElementById('toast');
	toast.textContent = text;
	toast.dataset.kind = kind;
	toast.hidden = false;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => {
		toast.hidden = true;
	}, TOAST_DURATION_MS);
}

// --- Actions

function sendAction(message) {
	if (connection?.send(message)) {
		navigator.vibrate?.(10);
	} else {
		showToast('Pas de connexion au serveur, action non envoyée.');
	}
}

document.getElementById('life-buttons').addEventListener('click', (event) => {
	const button = event.target.closest('button[data-delta]');
	if (button && selectedPlayerId !== null) {
		sendAction({ type: 'CHANGE_LIFE', player: selectedPlayerId, delta: Number(button.dataset.delta) });
	}
});

commanderRows.addEventListener('click', (event) => {
	const button = event.target.closest('button[data-from]');
	if (button && selectedPlayerId !== null) {
		sendAction({
			type: 'CHANGE_COMMANDER_DAMAGE',
			player: selectedPlayerId,
			fromPlayer: Number(button.dataset.from),
			delta: Number(button.dataset.damageDelta),
		});
	}
});

changePlayerButton.addEventListener('click', () => selectPlayer(null));

initCardSearch({
	onCardSent: (label, warnings) => {
		// En mode cartes, /table affiche la carte et ses avertissements, et ferme la fenêtre
		if (!CARDS_ONLY) {
			showToast([`« ${label} » est affichée sur la table.`, ...warnings].join(' '), 'success');
		}
	},
	// Carte envoyée ou recherche annulée : /table ferme la fenêtre (même origine uniquement)
	onClose: () => {
		if (CARDS_ONLY) {
			parent.postMessage({ type: 'mtg-companion:close-embedded' }, location.origin);
		}
	},
});
if (CARDS_ONLY) {
	openSendSearch();
}

initRulings();

initZoneBackground({
	getMe: () => currentGame?.players.find((player) => player.id === selectedPlayerId) ?? null,
	getGame: () => currentGame,
	send: sendAction,
	notify: showToast,
});

document.getElementById('hide-card').addEventListener('click', async () => {
	if (!await hideCard()) {
		showToast('Pas de connexion au serveur, la carte n\'a pas été fermée.');
	}
});
