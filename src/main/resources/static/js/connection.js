// Connexion WebSocket au serveur, partagée par toutes les pages.
// Le serveur envoie l'état complet à chaque connexion : une reconnexion suffit donc à se resynchroniser.

const RECONNECT_DELAYS_MS = [1000, 2000, 5000];
/**
 * Après une veille, une connexion peut sembler ouverte alors que le réseau l'a coupée sans prévenir.
 * Au-delà de cette durée en arrière-plan, on repart donc d'une connexion neuve.
 */
const FRESH_CONNECTION_AFTER_HIDDEN_MS = 3000;

/**
 * @param {object} handlers
 * @param {(message: object) => void} handlers.onState        reçoit chaque message STATE
 * @param {(text: string) => void} [handlers.onError]         reçoit le texte des messages ERROR
 * @param {() => void} [handlers.onOpen]                      à chaque (re)connexion, avant l'état complet
 * @param {(message: object) => void} [handlers.onCardShown]  carte affichée sur la table (CARD_SHOWN)
 * @param {() => void} [handlers.onCardHidden]                carte fermée (CARD_HIDDEN)
 * @returns {{ send: (message: object) => boolean }} send renvoie false si la connexion est coupée
 */
export function connectToGame({
	onState,
	onError = () => {},
	onOpen = () => {},
	onCardShown = () => {},
	onCardHidden = () => {},
}) {
	const statusElement = document.getElementById('connection-status');
	let socket = null;
	let failedAttempts = 0;
	let reconnectTimer = null;
	let hiddenSince = null;

	function showStatus(state, text) {
		// Sur la racine, pour que le CSS puisse atténuer les valeurs périmées pendant une coupure
		document.documentElement.dataset.connection = state;
		if (statusElement) {
			statusElement.dataset.state = state;
			statusElement.textContent = text;
		}
	}

	function open() {
		clearTimeout(reconnectTimer);
		reconnectTimer = null;
		if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
			return;
		}
		const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
		const current = new WebSocket(`${protocol}//${location.host}/ws`);
		socket = current;

		current.addEventListener('open', () => {
			failedAttempts = 0;
			showStatus('connected', 'Connecté');
			// Le serveur renvoie tout l'état juste après : ce qui était affiché avant la coupure est périmé
			onOpen();
		});
		current.addEventListener('message', (event) => {
			const message = JSON.parse(event.data);
			switch (message.type) {
				case 'STATE':
					onState(message);
					break;
				case 'ERROR':
					onError(message.message);
					break;
				case 'CARD_SHOWN':
					onCardShown(message);
					break;
				case 'CARD_HIDDEN':
					onCardHidden();
					break;
			}
		});
		current.addEventListener('close', () => {
			// Une connexion remplacée volontairement ne doit pas déclencher de reconnexion
			if (socket !== current) {
				return;
			}
			showStatus('disconnected', 'Connexion perdue, reconnexion…');
			scheduleReconnect();
		});
	}

	function openFreshConnection() {
		const previous = socket;
		socket = null;
		previous?.close();
		showStatus('connecting', 'Reconnexion…');
		open();
	}

	function scheduleReconnect() {
		if (reconnectTimer) {
			return;
		}
		const delay = RECONNECT_DELAYS_MS[Math.min(failedAttempts, RECONNECT_DELAYS_MS.length - 1)];
		failedAttempts++;
		reconnectTimer = setTimeout(open, delay);
	}

	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'hidden') {
			hiddenSince = Date.now();
			return;
		}
		const longAbsence = hiddenSince !== null && Date.now() - hiddenSince > FRESH_CONNECTION_AFTER_HIDDEN_MS;
		hiddenSince = null;
		if (longAbsence) {
			openFreshConnection();
		} else {
			open();
		}
	});
	// Page restaurée depuis le cache de navigation (bouton Retour, Safari sur iPhone) : sa connexion est morte
	window.addEventListener('pageshow', (event) => {
		if (event.persisted) {
			openFreshConnection();
		}
	});
	window.addEventListener('online', open);

	showStatus('connecting', 'Connexion…');
	open();

	return {
		send(message) {
			if (socket && socket.readyState === WebSocket.OPEN) {
				socket.send(JSON.stringify(message));
				return true;
			}
			return false;
		},
	};
}
