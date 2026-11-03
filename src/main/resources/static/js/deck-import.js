// Import d'une decklist Moxfield (texte) : analyse par le serveur, validation (nom, commandants, cartes à
// corriger), puis enregistrement. Le même écran sert à remplacer la liste d'un deck déjà importé.

const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';
const MAX_FILE_BYTES = 100_000;
const STATUS_LABELS = {
	NOT_FOUND: 'Carte non reconnue',
	UNREADABLE: 'Ligne illisible',
	NAME_ONLY: 'Impression introuvable : carte reconnue par son nom',
};

const panel = document.getElementById('deck-import');
const statusElement = document.getElementById('deck-import-status');
const sourceForm = document.getElementById('deck-import-source');
const textArea = document.getElementById('deck-import-text');
const fileInput = document.getElementById('deck-import-file');
const reviewForm = document.getElementById('deck-import-review');
const nameInput = document.getElementById('deck-import-name');
const summary = document.getElementById('deck-import-summary');
const commanderHint = document.getElementById('deck-import-commander-hint');
const commanderList = document.getElementById('deck-import-commander-list');
const problems = document.getElementById('deck-import-problems');
const problemList = document.getElementById('deck-import-problem-list');
const ignoreBox = document.getElementById('deck-import-ignore');
const duplicate = document.getElementById('deck-import-duplicate');
const duplicateText = document.getElementById('deck-import-duplicate-text');
const saveButton = document.getElementById('deck-import-save');

let onSaved = () => {};
/** Deck importé dont on remplace la liste, ou null pour un nouveau deck. */
let replaceTarget = null;
/** Deck déjà nommé ainsi, proposé en mise à jour après un conflit. */
let conflict = null;
let busy = false;

export function initDeckImport(options) {
	onSaved = options.onSaved;
	document.getElementById('deck-import-open').addEventListener('click', () => open(null));
	document.getElementById('close-deck-import').addEventListener('click', close);
	document.getElementById('deck-import-cancel').addEventListener('click', close);
	document.getElementById('deck-import-back').addEventListener('click', () => showSource());
	sourceForm.addEventListener('submit', (event) => {
		event.preventDefault();
		analyze();
	});
	fileInput.addEventListener('change', readFile);
	reviewForm.addEventListener('submit', (event) => {
		event.preventDefault();
		save(null);
	});
	document.getElementById('deck-import-update').addEventListener('click', () => save(conflict?.existingId ?? null));
	document.getElementById('deck-import-rename').addEventListener('click', () => {
		hideConflict();
		nameInput.focus();
		nameInput.select();
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
}

/** @param {{ id: string, name: string } | null} target deck importé à remplacer, ou null pour un nouveau */
export function openDeckImport(target) {
	open(target);
}

function open(target) {
	replaceTarget = target;
	panel.hidden = false;
	document.body.classList.add('modal-open');
	document.getElementById('deck-import-title').textContent = target ? `Remplacer la liste de « ${target.name} »` : 'Importer un deck';
	textArea.value = '';
	fileInput.value = '';
	showSource();
}

function close() {
	panel.hidden = true;
	// Le sélecteur de deck est encore ouvert dessous : il garde le blocage du défilement
	showStatus('');
}

function showStatus(text, kind = '') {
	statusElement.textContent = text;
	statusElement.dataset.kind = kind;
}

function showSource() {
	sourceForm.hidden = false;
	reviewForm.hidden = true;
	hideConflict();
	showStatus('');
	textArea.focus();
}

function hideConflict() {
	conflict = null;
	duplicate.hidden = true;
}

async function readFile() {
	const file = fileInput.files[0];
	if (!file) {
		return;
	}
	if (!/\.txt$/i.test(file.name)) {
		showStatus('Seuls les fichiers .txt sont acceptés.', 'error');
		fileInput.value = '';
		return;
	}
	if (file.size > MAX_FILE_BYTES) {
		showStatus('Fichier trop volumineux (100 Ko au plus).', 'error');
		fileInput.value = '';
		return;
	}
	textArea.value = await file.text();
	showStatus(`Fichier « ${file.name.slice(0, 60)} » chargé.`);
}

async function request(method, url, body) {
	const response = await fetch(url, {
		method,
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body),
	});
	const data = await response.json().catch(() => null);
	return { response, data };
}

async function analyze() {
	if (busy) {
		return;
	}
	if (textArea.value.trim() === '') {
		showStatus('Collez une decklist ou choisissez un fichier.', 'error');
		return;
	}
	busy = true;
	showStatus('Analyse de la liste et recherche des cartes…');
	try {
		const { response, data } = await request('POST', '/api/decks/custom/preview', { text: textArea.value });
		if (!response.ok) {
			showStatus(data?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		showReview(data);
	} catch {
		showStatus(NO_CONNECTION, 'error');
	} finally {
		busy = false;
	}
}

function showReview(draft) {
	sourceForm.hidden = true;
	reviewForm.hidden = false;
	hideConflict();
	showStatus('');
	nameInput.value = replaceTarget ? replaceTarget.name : draft.suggestedName;
	ignoreBox.checked = false;

	const issues = draft.lines.filter((line) => line.status !== 'OK');
	const blocking = issues.filter((line) => line.status === 'NOT_FOUND' || line.status === 'UNREADABLE');
	summary.textContent = `${draft.totalCards} carte(s) au total, ${draft.recognizedCards} reconnue(s).`;

	commanderHint.textContent = draft.commandersDeclared
		? 'Commandants lus dans la liste. Vous pouvez les modifier.'
		: 'La liste n\'indique pas le commandant : cochez-le (la première ligne est proposée).';
	commanderList.replaceChildren(...draft.lines.filter((line) => line.card !== null).map((line) => {
		const input = document.createElement('input');
		input.type = 'checkbox';
		input.value = String(line.index);
		input.checked = line.commander;
		const label = document.createElement('label');
		label.className = 'deck-import-check';
		label.append(input, ` ${line.card.name}${line.count > 1 ? ` ×${line.count}` : ''}`);
		const item = document.createElement('li');
		item.append(label);
		return item;
	}));

	problems.hidden = issues.length === 0;
	problemList.replaceChildren(...issues.map((line) => {
		const item = document.createElement('li');
		item.className = 'deck-import-problem';
		const raw = document.createElement('strong');
		raw.textContent = line.raw;
		const reason = document.createElement('span');
		reason.textContent = ` — ${STATUS_LABELS[line.status]}${line.card ? ` (${line.card.name})` : ''}`;
		item.append(raw, reason);
		return item;
	}));
	// L'option d'écarter n'a de sens que s'il reste des lignes non reconnues
	ignoreBox.closest('label').hidden = blocking.length === 0;
	saveButton.disabled = false;
	nameInput.focus();
}

async function save(replaceId) {
	if (busy) {
		return;
	}
	busy = true;
	saveButton.disabled = true;
	showStatus('Enregistrement…');
	const commanderLines = [...commanderList.querySelectorAll('input:checked')].map((input) => Number(input.value));
	try {
		const { response, data } = await request('POST', '/api/decks/custom', {
			text: textArea.value,
			name: nameInput.value,
			commanderLines,
			replaceId: replaceId ?? replaceTarget?.id ?? null,
			ignoreProblems: ignoreBox.checked,
		});
		if (response.status === 409) {
			conflict = data;
			duplicateText.textContent = `${data.message} Mettre à jour ce deck, choisir un autre nom, ou annuler ?`;
			duplicate.hidden = false;
			showStatus('');
			return;
		}
		if (!response.ok) {
			showStatus(data?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		panel.hidden = true;
		showStatus('');
		onSaved(data);
	} catch {
		showStatus(NO_CONNECTION, 'error');
	} finally {
		busy = false;
		saveButton.disabled = false;
	}
}
