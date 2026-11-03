import { artCreditText, artUrl } from './art-credit.js';
import { connectToGame } from './connection.js';
import { LifeChangeIndicator } from './life-change.js';

/** Réserve de PV de commandant par adversaire, affichée « à l'envers » (le serveur compte les dégâts reçus). */
const COMMANDER_POOL = 21;

const tableElement = document.getElementById('table');
const playerTemplate = document.getElementById('player-template');

/** Panneaux affichés, par id de joueur. Recréés seulement si la composition de la partie change. */
let panels = new Map();
let renderedLayout = null;

connectToGame({
	onState(message) {
		document.getElementById('join-address').textContent = `${message.serverAddress}/joueur`;
		renderGame(message.game);
		closeSettingsIfPlayerGone(message.game);
	},
	// Après une coupure, le serveur renvoie la carte affichée s'il y en a encore une
	onOpen: hideCard,
	onCardShown: showCard,
	onCardHidden: hideCard,
});

// --- Carte Magic affichée par-dessus la partie

const cardOverlay = document.getElementById('card-overlay');

function showCard({ card, remainingMs, durationMs }) {
	// Une langue : les faces côte à côte. Deux langues : une colonne par langue, une ligne par face
	const bilingual = card.variants.length > 1;
	const faceCount = Math.max(...card.variants.map((variant) => variant.images.length));
	const container = document.getElementById('card-overlay-images');
	container.style.setProperty('--columns', bilingual ? card.variants.length : faceCount);
	container.style.setProperty('--rows', bilingual ? faceCount : 1);
	container.replaceChildren(...card.variants.flatMap((variant, variantIndex) => variant.images.map((url, faceIndex) => {
		const image = document.createElement('img');
		image.src = url;
		image.alt = `${card.name} (${variant.lang.toUpperCase()})`;
		const figure = document.createElement('figure');
		figure.style.gridColumn = (bilingual ? variantIndex : faceIndex) + 1;
		figure.style.gridRow = (bilingual ? faceIndex : 0) + 1;
		if (bilingual) {
			const caption = document.createElement('figcaption');
			caption.textContent = variant.lang.toUpperCase();
			figure.append(caption);
		}
		figure.append(image);
		return figure;
	})));

	const warning = document.getElementById('card-overlay-warning');
	warning.textContent = card.warnings.join(' ');
	warning.hidden = card.warnings.length === 0;

	cardOverlay.hidden = false;
	startProgressBar(remainingMs, durationMs);
}

function hideCard() {
	cardOverlay.hidden = true;
}

// Fermeture manuelle : on masque tout de suite, et le serveur annule sa minuterie et prévient les téléphones
document.getElementById('card-overlay-close').addEventListener('click', () => {
	hideCard();
	fetch('/api/cards/hide', { method: 'POST' }).catch(() => {
		// Injoignable : la carte reste masquée ici, la fermeture automatique du serveur fera le reste
	});
});

/** Barre qui se vide jusqu'à la fermeture automatique, décidée par le serveur. */
function startProgressBar(remainingMs, durationMs) {
	const bar = document.getElementById('card-overlay-progress-bar');
	bar.style.transition = 'none';
	bar.style.width = `${Math.min(100, (remainingMs / durationMs) * 100)}%`;
	bar.getBoundingClientRect(); // force le navigateur à appliquer la largeur de départ avant l'animation
	bar.style.transition = `width ${remainingMs}ms linear`;
	bar.style.width = '0%';
}

function renderGame(game) {
	const layout = JSON.stringify([game.format, game.players.map((player) => player.name)]);
	if (layout !== renderedLayout) {
		buildPanels(game);
		renderedLayout = layout;
	}
	game.players.forEach((player) => updatePanel(panels.get(player.id), player));
}

function buildPanels(game) {
	tableElement.dataset.count = String(game.players.length);
	tableElement.dataset.format = game.format;
	const namesById = new Map(game.players.map((player) => [player.id, player.name]));

	panels = new Map();
	tableElement.replaceChildren(...game.players.map((player) => {
		const root = playerTemplate.content.firstElementChild.cloneNode(true);
		root.dataset.player = String(player.id);
		root.querySelector('.player-name').textContent = player.name;
		const settingsButton = root.querySelector('.player-settings');
		settingsButton.setAttribute('aria-label', `Paramètres de ${player.name}`);
		settingsButton.addEventListener('click', () => openSettings({
			title: player.name,
			url: `/joueur?embedded=true&playerId=${player.id}`,
			playerId: player.id,
			opener: settingsButton,
		}));

		const damageList = root.querySelector('.commander-damage');
		const damageAmounts = new Map();
		for (const opponentId of Object.keys(player.commanderDamage).map(Number)) {
			const item = document.createElement('li');
			item.dataset.player = String(opponentId);
			const from = document.createElement('span');
			from.className = 'from';
			from.textContent = namesById.get(opponentId);
			const amount = document.createElement('span');
			amount.className = 'amount';
			item.append(from, amount);
			damageList.append(item);
			damageAmounts.set(opponentId, { item, amount });
		}

		panels.set(player.id, {
			root,
			art: root.querySelector('.player-art'),
			credit: root.querySelector('.player-credit'),
			artKey: '',
			life: root.querySelector('.life'),
			lifeChange: new LifeChangeIndicator(root.querySelector('.life-change')),
			damageAmounts,
		});
		return root;
	}));
}

/** L'image n'est redemandée que si l'illustration change : les mises à jour de PV n'y touchent pas. */
function updateArt(panel, background) {
	const key = background ? JSON.stringify(background) : '';
	if (key === panel.artKey) {
		return;
	}
	panel.artKey = key;
	panel.root.classList.remove('has-art');
	panel.art.style.backgroundImage = '';
	panel.credit.textContent = '';
	if (!background) {
		return;
	}
	const url = artUrl(background);
	panel.art.style.backgroundImage = `url("${url}")`;
	panel.art.style.backgroundPosition = `${background.positionX}% ${background.positionY}%`;
	// Sans l'image (cache vidé…), ni panneaux sombres ni crédit : la zone garde son fond par défaut
	const probe = new Image();
	probe.onload = () => {
		if (panel.artKey === key) {
			panel.credit.textContent = artCreditText(background);
			panel.root.classList.add('has-art');
		}
	};
	probe.src = url;
}

function updatePanel(panel, player) {
	updateArt(panel, player.background);
	panel.root.classList.toggle('eliminated', player.eliminated);
	panel.life.textContent = String(player.life);
	panel.lifeChange.update(player.life);
	for (const [opponentId, { item, amount }] of panel.damageAmounts) {
		const remaining = COMMANDER_POOL - (player.commanderDamage[opponentId] ?? 0);
		amount.textContent = String(remaining);
		item.classList.toggle('none', remaining === COMMANDER_POOL);
		item.classList.toggle('lethal', remaining <= 0);
	}
}

// --- Fenêtres de configuration : /config et /joueur dans une iframe, sans quitter la table

const settingsDialog = document.getElementById('settings-dialog');
const settingsFrame = document.getElementById('settings-frame');
/** Joueur affiché dans la modale, ou null pour /config. */
let settingsPlayerId = null;
let settingsOpener = null;

function openSettings({ title, url, playerId = null, opener }) {
	if (settingsDialog.open) {
		return;
	}
	settingsPlayerId = playerId;
	settingsOpener = opener;
	document.getElementById('settings-title').textContent = title;
	settingsFrame.title = title;
	settingsFrame.src = url;
	document.body.classList.add('settings-open');
	settingsDialog.showModal();
	document.getElementById('settings-close').focus();
}

// Aussi déclenché par Échap (natif) : on libère l'iframe, donc sa connexion WebSocket
settingsDialog.addEventListener('close', () => {
	settingsFrame.src = 'about:blank';
	settingsPlayerId = null;
	document.body.classList.remove('settings-open');
	settingsOpener?.focus();
	settingsOpener = null;
});

settingsFrame.addEventListener('load', () => {
	// Échap pressé dans l'iframe n'atteint pas le <dialog> ; un panneau interne ouvert se ferme d'abord
	settingsFrame.contentWindow?.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !settingsFrame.contentDocument.querySelector('.card-search:not([hidden])')) {
			settingsDialog.close();
		}
	}, true);
});

document.getElementById('settings-close').addEventListener('click', () => settingsDialog.close());
document.getElementById('open-cards').addEventListener('click', (event) => openSettings({
	title: 'Cartes',
	url: '/joueur?embedded=cards',
	opener: event.currentTarget,
}));
// La recherche intégrée signale sa fin (carte envoyée ou annulation) : seul l'iframe ouvert, de même origine
window.addEventListener('message', (event) => {
	if (event.origin === location.origin
		&& event.source === settingsFrame.contentWindow
		&& event.data?.type === 'mtg-companion:close-embedded') {
		settingsDialog.close();
	}
});
document.getElementById('open-config').addEventListener('click', (event) => openSettings({
	title: 'Configuration',
	url: '/config?embedded=true',
	opener: event.currentTarget,
}));

function closeSettingsIfPlayerGone(game) {
	if (settingsDialog.open && settingsPlayerId !== null
		&& !game.players.some((player) => player.id === settingsPlayerId)) {
		settingsDialog.close();
	}
}

// Double-clic (ou double-tap sur tablette) : plein écran, pratique sur une télé
document.addEventListener('dblclick', (event) => {
	if (event.target.closest('.settings-button, .settings-dialog')) {
		return;
	}
	if (document.fullscreenElement) {
		document.exitFullscreen();
	} else {
		document.documentElement.requestFullscreen?.();
	}
});
