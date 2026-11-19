// Journal de la partie : actions de chaque tour (PV, dégâts de commandant), avec retour en arrière
// et choix manuel du joueur dont c'est le tour. Le journal est tenu par le serveur et partagé.

const panel = document.getElementById('game-log');
const turnPicker = document.getElementById('game-log-players');
const list = document.getElementById('game-log-list');
const empty = document.getElementById('game-log-empty');

let sendAction = () => {};
let getGame = () => null;
let log = { turn: 1, entries: [] };

export function initGameLog({ send, game }) {
	sendAction = send;
	getGame = game;
	document.getElementById('open-game-log').addEventListener('click', open);
	document.getElementById('close-game-log').addEventListener('click', close);
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
}

/** Reçoit chaque message LOG ; ne redessine que si le panneau est ouvert. */
export function updateLog(message) {
	log = message;
	if (!panel.hidden) {
		render();
	}
}

/** À rappeler quand l'état change : les boutons « donner la main » suivent le joueur actif. */
export function refreshGameLog() {
	if (!panel.hidden) {
		render();
	}
}

function open() {
	render();
	panel.hidden = false;
	document.body.classList.add('modal-open');
}

function close() {
	panel.hidden = true;
	document.body.classList.remove('modal-open');
}

function nameOf(playerId) {
	return getGame()?.players.find((player) => player.id === playerId)?.name ?? `Joueur ${playerId}`;
}

function describe(entry) {
	const amount = Math.abs(entry.delta);
	switch (entry.type) {
		case 'LIFE':
			return `${nameOf(entry.player)} : ${amount} PV ${entry.delta < 0 ? 'perdus' : 'gagnés'}`;
		case 'COMMANDER_DAMAGE':
			return `${nameOf(entry.player)} : ${amount} dégât${amount > 1 ? 's' : ''} de commandant de ${nameOf(entry.fromPlayer)}`
				+ (entry.delta < 0 ? ' retirés' : '');
		case 'NEXT_TURN':
			return `Fin de tour, la main passe à ${nameOf(entry.player)}`;
		case 'SET_ACTIVE':
			return `Main donnée à ${nameOf(entry.player)}`;
		default:
			return entry.type;
	}
}

/**
 * Additionne, dans un même tour, les PV et les dégâts de commandant d'un même joueur (et d'un même commandant) :
 * -5, -5, -1 donnent « 11 PV perdus ». La ligne reprend la première action du groupe (annuler la ligne annule
 * donc tout le groupe, et ce qui a suivi) ; un total nul n'est pas affiché. `index` : rang de cette première action.
 */
function summarize(entries) {
	const rows = [];
	const groups = new Map();
	entries.forEach((entry, index) => {
		if (entry.type !== 'LIFE' && entry.type !== 'COMMANDER_DAMAGE') {
			rows.push({ ...entry, index });
			return;
		}
		const key = `${entry.turn}|${entry.activePlayer}|${entry.type}|${entry.player}|${entry.fromPlayer}`;
		const group = groups.get(key);
		if (group) {
			group.delta += entry.delta;
		} else {
			const row = { ...entry, index };
			groups.set(key, row);
			rows.push(row);
		}
	});
	return rows.filter((row) => row.delta !== 0 || (row.type !== 'LIFE' && row.type !== 'COMMANDER_DAMAGE'));
}

function render() {
	const game = getGame();
	if (!game) {
		return;
	}

	turnPicker.replaceChildren(...game.players.map((player) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'zone-button';
		button.textContent = player.name;
		button.disabled = player.id === game.activePlayer;
		button.addEventListener('click', () => sendAction({ type: 'SET_ACTIVE_PLAYER', player: player.id }));
		const item = document.createElement('li');
		item.append(button);
		return item;
	}));

	empty.hidden = log.entries.length > 0;
	const newestFirst = summarize(log.entries).reverse();
	const items = [];
	let previousTurn = null;
	newestFirst.forEach((entry) => {
		const turnKey = `${entry.turn}|${entry.activePlayer}`;
		if (turnKey !== previousTurn) {
			const heading = document.createElement('li');
			heading.className = 'game-log-turn';
			heading.textContent = `Tour ${entry.turn} · ${nameOf(entry.activePlayer)}`;
			items.push(heading);
			previousTurn = turnKey;
		}
		const item = document.createElement('li');
		item.className = 'game-log-entry';
		const text = document.createElement('span');
		text.textContent = describe(entry);
		const undo = document.createElement('button');
		undo.type = 'button';
		undo.className = 'my-deck-button';
		undo.textContent = 'Annuler';
		undo.addEventListener('click', () => {
			const later = log.entries.length - 1 - entry.index; // actions plus récentes, annulées avec celle-ci
			const warning = later > 0 ? `\n\n${later} action${later > 1 ? 's' : ''} plus récente${later > 1 ? 's' : ''} sera${later > 1 ? 'ont' : ''} aussi annulée${later > 1 ? 's' : ''}.` : '';
			if (confirm(`Annuler « ${describe(entry)} » ?${warning}`)) {
				sendAction({ type: 'UNDO_TO', entryId: entry.id });
			}
		});
		item.append(text, undo);
		items.push(item);
	});
	list.replaceChildren(...items);
}
