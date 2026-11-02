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
	},
});

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
			life: root.querySelector('.life'),
			lifeChange: new LifeChangeIndicator(root.querySelector('.life-change')),
			damageAmounts,
		});
		return root;
	}));
}

function updatePanel(panel, player) {
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

// Double-clic (ou double-tap sur tablette) : plein écran, pratique sur une télé
document.addEventListener('dblclick', () => {
	if (document.fullscreenElement) {
		document.exitFullscreen();
	} else {
		document.documentElement.requestFullscreen?.();
	}
});
