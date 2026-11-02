import { connectToGame } from './connection.js';

const DEFAULT_STARTING_LIFE = { STANDARD: 20, COMMANDER: 40 };
const FORMAT_LABELS = { STANDARD: 'Standard', COMMANDER: 'Commander' };

const form = document.getElementById('game-form');
const startingLifeInput = document.getElementById('starting-life');
const nameInputs = [...document.querySelectorAll('#player-names input')];
const feedback = document.getElementById('feedback');

let currentGame = null;
let formFilledFromServer = false;
/** Message de succès à afficher quand le serveur aura confirmé l'action par un nouvel état. */
let pendingSuccessMessage = null;

const connection = connectToGame({
	onState(message) {
		currentGame = message.game;
		renderAddresses(message);
		renderCurrentGame(message.game);
		// On ne pré-remplit le formulaire qu'une fois, pour ne pas écraser une saisie en cours
		if (!formFilledFromServer) {
			fillForm(message.game);
			formFilledFromServer = true;
		}
		if (pendingSuccessMessage) {
			showFeedback(pendingSuccessMessage, 'success');
			pendingSuccessMessage = null;
		}
	},
	onError(text) {
		pendingSuccessMessage = null;
		showFeedback(text, 'error');
	},
});

function renderAddresses({ serverAddress, otherAddresses }) {
	const phoneLink = document.getElementById('phone-address');
	phoneLink.textContent = `${serverAddress}/joueur`;
	phoneLink.href = `${serverAddress}/joueur`;

	const tableLink = document.getElementById('table-address');
	tableLink.textContent = `${serverAddress}/table`;
	tableLink.href = `${serverAddress}/table`;

	const others = document.getElementById('other-addresses');
	others.hidden = otherAddresses.length === 0;
	others.textContent = `Si ça ne marche pas, essayez : ${otherAddresses.map((a) => `${a}/joueur`).join(', ')}`;

	document.getElementById('localhost-warning').hidden = !serverAddress.includes('localhost');
}

function renderCurrentGame(game) {
	document.getElementById('current-summary').textContent =
		`${FORMAT_LABELS[game.format]} · ${game.players.length} joueurs · ${game.startingLife} PV de départ`;

	const list = document.getElementById('current-players');
	list.replaceChildren(...game.players.map((player) => {
		const item = document.createElement('li');
		item.classList.toggle('eliminated', player.eliminated);
		const name = document.createElement('span');
		name.textContent = player.name;
		const life = document.createElement('span');
		life.className = 'life';
		life.textContent = `${player.life} PV`;
		item.append(name, life);
		return item;
	}));
}

function fillForm(game) {
	form.elements.format.value = game.format;
	startingLifeInput.value = game.startingLife;
	form.elements.playerCount.value = String(game.players.length);
	game.players.forEach((player, index) => {
		// Les noms par défaut (« Joueur N ») restent en placeholder, pas en valeur
		nameInputs[index].value = player.name === `Joueur ${index + 1}` ? '' : player.name;
	});
	updateVisibleNameInputs();
}

function selectedPlayerCount() {
	return Number(form.elements.playerCount.value);
}

function updateVisibleNameInputs() {
	const count = selectedPlayerCount();
	nameInputs.forEach((input, index) => {
		input.hidden = index >= count;
	});
}

/** Une partie est « en cours » dès qu'un compteur a bougé depuis le lancement. */
function isGameInProgress(game) {
	return game.players.some((player) =>
		player.life !== game.startingLife
		|| Object.values(player.commanderDamage).some((damage) => damage > 0));
}

function showFeedback(text, kind) {
	feedback.textContent = text;
	feedback.dataset.kind = kind;
}

function sendOrReport(message, successMessage) {
	if (connection.send(message)) {
		pendingSuccessMessage = successMessage;
		showFeedback('Envoi…', 'info');
	} else {
		showFeedback('Pas de connexion au serveur, réessayez dans un instant.', 'error');
	}
}

form.addEventListener('change', (event) => {
	if (event.target.name === 'format') {
		startingLifeInput.value = DEFAULT_STARTING_LIFE[event.target.value];
	}
	if (event.target.name === 'playerCount') {
		updateVisibleNameInputs();
	}
});

form.addEventListener('submit', (event) => {
	event.preventDefault();
	if (!form.reportValidity()) {
		return;
	}
	if (currentGame && isGameInProgress(currentGame)
		&& !confirm('Lancer une nouvelle partie ?\n\nCela annulera la partie en cours.')) {
		return;
	}
	sendOrReport({
		type: 'START_GAME',
		format: form.elements.format.value,
		startingLife: Number(startingLifeInput.value),
		players: nameInputs.slice(0, selectedPlayerCount()).map((input) => input.value.trim()),
	}, 'Partie lancée.');
});

document.getElementById('reset-button').addEventListener('click', () => {
	const startingLife = currentGame ? currentGame.startingLife : '';
	if (!confirm(`Réinitialiser la partie en cours ?\n\nTous les joueurs reviennent à ${startingLife} PV `
		+ 'et les dégâts de commandant sont remis à zéro.')) {
		return;
	}
	sendOrReport({ type: 'RESET' }, 'Partie réinitialisée.');
});

updateVisibleNameInputs();
