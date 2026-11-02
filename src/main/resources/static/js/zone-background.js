// Choix de l'illustration du fond de la zone du joueur sur la table.
// Deux sources : une carte Scryfall (recherche existante) ou la bibliothèque d'images partagée.
// Le cadrage (position X/Y) se règle pour chaque disposition (2, 3 ou 4 joueurs) sur un aperçu aux
// proportions de la zone ; il est mémorisé par joueur, image et disposition, et envoyé à l'application.

import { artCreditText } from './art-credit.js';
import { openCardSearch } from './card-search.js';

/** Écran de table supposé 16/9 pour l'aperçu : colonnes × lignes de la disposition, par nombre de joueurs. */
const LAYOUTS = { 2: [2, 1], 3: [3, 1], 4: [2, 2] };
const LAYOUT_HINTS = {
	2: 'Zone presque carrée : seule une partie de la largeur de l\'image est visible, réglez la position horizontale.',
	3: 'Zone étroite et haute : seule une bande verticale de l\'image est visible, réglez la position horizontale pour garder le sujet.',
	4: 'Zone large et basse : seule une bande horizontale de l\'image est visible, réglez la position verticale.',
};
const MAX_UPLOAD_BYTES = 15 * 1024 * 1024;
const NO_CONNECTION = 'Pas de connexion au serveur, réessayez dans un instant.';

/**
 * Coin du bloc d'informations dans la zone du joueur (numéro de 1 à n), comme dans table.css :
 * dans le coin intérieur, au bas au centre à 3 joueurs.
 */
function infoCorner(count, playerId) {
	if (count === 3) {
		return { v: 'bottom', h: 'center' };
	}
	const slot = ((playerId - 1) % count) + 1;
	const left = slot % 2 === 1;
	const top = count === 4 && slot > 2;
	return { v: top ? 'top' : 'bottom', h: left ? 'right' : 'left' };
}

const panel = document.getElementById('zone-background');
const statusElement = document.getElementById('zone-background-status');
const editor = document.getElementById('zone-editor');
const preview = document.getElementById('zone-preview');
const faceGroup = document.getElementById('zone-faces');
const libraryList = document.getElementById('zone-library');
const sliderX = document.getElementById('zone-x');
const sliderY = document.getElementById('zone-y');

/**
 * Image en cours de réglage : { kind: 'SCRYFALL', cardName, printId, arts, face }
 * ou { kind: 'UPLOAD', imageId, name, url }.
 */
let selection = null;
/** Cadrages déjà enregistrés pour ce joueur : identifiant d'image → disposition → { x, y }. */
let savedFramings = {};
/** Cadrages modifiés depuis l'ouverture : seuls ceux-là sont envoyés. */
let edits = {};
let activeLayout = 4;
let library = [];
let callbacks = null;

/**
 * @param {{ getMe: () => object | null, getGame: () => object | null,
 *           send: (message: object) => void, notify: (text: string, kind?: string) => void }} options
 */
export function initZoneBackground(options) {
	callbacks = options;
	document.getElementById('open-zone-background').addEventListener('click', start);
	document.getElementById('close-zone-background').addEventListener('click', close);
	document.getElementById('zone-search').addEventListener('click', searchCard);
	document.getElementById('zone-apply').addEventListener('click', apply);
	document.getElementById('zone-clear').addEventListener('click', clear);
	document.getElementById('zone-import').addEventListener('change', importImage);
	panel.querySelector('.zone-tabs').addEventListener('click', (event) => {
		const tab = event.target.closest('button[data-tab]');
		if (tab) {
			showTab(tab.dataset.tab);
		}
	});
	document.getElementById('zone-layouts').addEventListener('click', (event) => {
		const button = event.target.closest('button[data-layout]');
		if (button) {
			activeLayout = Number(button.dataset.layout);
			renderEditor();
		}
	});
	libraryList.addEventListener('click', (event) => {
		const remove = event.target.closest('button[data-delete]');
		const choice = event.target.closest('button[data-choose]');
		if (remove) {
			deleteImage(remove.dataset.delete);
		} else if (choice) {
			selectUpload(library.find((image) => image.id === choice.dataset.choose));
		}
	});
	faceGroup.addEventListener('click', (event) => {
		const button = event.target.closest('button[data-face]');
		if (button && selection) {
			selection.face = Number(button.dataset.face);
			renderEditor();
		}
	});
	const onSlider = () => {
		const id = imageKey();
		if (id) {
			edits[id] = { ...edits[id], [activeLayout]: { x: Number(sliderX.value), y: Number(sliderY.value) } };
			renderPreview();
		}
	};
	sliderX.addEventListener('input', onSlider);
	sliderY.addEventListener('input', onSlider);
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && !panel.hidden) {
			close();
		}
	});
}

async function start() {
	const me = callbacks.getMe();
	const game = callbacks.getGame();
	if (!me || !game) {
		return;
	}
	selection = null;
	edits = {};
	activeLayout = game.players.length;
	editor.hidden = true;
	showStatus('');
	panel.hidden = false;
	document.body.classList.add('modal-open');

	const background = me.background;
	showTab(background?.kind === 'UPLOAD' ? 'library' : 'scryfall');
	savedFramings = await fetchJson(`/api/player-art/${me.id}/framing`) ?? {};
	if (!background) {
		return;
	}
	// Réglage du fond actuel (déjà en cache ou dans la bibliothèque : pas d'appel à Scryfall)
	if (background.kind === 'UPLOAD') {
		await refreshLibrary();
		const image = library.find((candidate) => candidate.id === background.imageId);
		if (image) {
			selectUpload(image);
		} else {
			showStatus('L\'image de votre fond n\'est plus dans la bibliothèque.', 'warning');
		}
	} else {
		await loadArts(background.cardName, background.printId, background.face);
	}
}

// --- Sources

function showTab(name) {
	for (const tab of panel.querySelectorAll('.zone-tab')) {
		tab.setAttribute('aria-selected', String(tab.dataset.tab === name));
	}
	document.getElementById('zone-pane-scryfall').hidden = name !== 'scryfall';
	document.getElementById('zone-pane-library').hidden = name !== 'library';
	if (name === 'library') {
		refreshLibrary();
	}
}

function searchCard() {
	panel.hidden = true;
	openCardSearch(
		'Illustration de ma zone',
		(card) => loadArts(card.name, card.printId, 0),
		// Recherche refermée sans choix : retour à la fenêtre de réglage
		() => {
			panel.hidden = false;
			document.body.classList.add('modal-open');
		});
}

async function loadArts(cardName, printId, face) {
	panel.hidden = false;
	document.body.classList.add('modal-open');
	showTab('scryfall');
	selection = null;
	editor.hidden = true;
	showStatus('Chargement de l\'illustration…');
	try {
		const query = new URLSearchParams({ name: cardName });
		if (printId) {
			query.set('printId', printId);
		}
		const response = await fetch(`/api/cards/arts?${query}`);
		if (!response.ok) {
			const body = await response.json().catch(() => null);
			showStatus(body?.message ?? (response.status === 404
				? 'Cette impression n\'a pas d\'illustration utilisable : choisissez-en une autre.'
				: `Erreur du serveur (HTTP ${response.status}).`), response.status === 404 ? 'warning' : 'error');
			return;
		}
		const found = await response.json();
		const knownFace = found.arts.some((art) => art.face === face);
		selection = {
			kind: 'SCRYFALL',
			cardName: found.name,
			printId: found.printId,
			arts: found.arts,
			face: knownFace ? face : found.arts[0].face,
		};
		showStatus(`${found.name} : réglez le cadrage de chaque disposition, puis appliquez.`);
		renderEditor();
	} catch {
		showStatus(NO_CONNECTION, 'error');
	}
}

function selectUpload(image) {
	if (!image) {
		return;
	}
	selection = { kind: 'UPLOAD', imageId: image.id, name: image.name, url: image.url };
	showStatus(`${image.name} : réglez le cadrage de chaque disposition, puis appliquez.`);
	renderEditor();
}

// --- Bibliothèque

async function refreshLibrary() {
	const images = await fetchJson('/api/user-images');
	if (images === null) {
		showStatus(NO_CONNECTION, 'error');
		return;
	}
	library = images;
	libraryList.replaceChildren(...library.map((image) => {
		const item = document.createElement('li');
		const choose = document.createElement('button');
		choose.type = 'button';
		choose.className = 'zone-library-choice';
		choose.dataset.choose = image.id;
		const thumbnail = document.createElement('img');
		thumbnail.loading = 'lazy';
		thumbnail.decoding = 'async';
		thumbnail.alt = '';
		thumbnail.src = image.url;
		const name = document.createElement('span');
		name.className = 'zone-library-name';
		name.textContent = image.name;
		const size = document.createElement('span');
		size.className = 'zone-library-size';
		size.textContent = `${image.width} × ${image.height} px · ${formatBytes(image.bytes)}`;
		choose.append(thumbnail, name, size);

		const remove = document.createElement('button');
		remove.type = 'button';
		remove.className = 'zone-library-delete';
		remove.dataset.delete = image.id;
		remove.setAttribute('aria-label', `Supprimer ${image.name}`);
		remove.textContent = 'Supprimer';
		item.append(choose, remove);
		return item;
	}));
	if (library.length === 0) {
		showStatus('La bibliothèque est vide : importez une image.');
	}
}

async function importImage(event) {
	const input = event.target;
	const file = input.files[0];
	input.value = '';
	if (!file) {
		return;
	}
	if (file.size > MAX_UPLOAD_BYTES) {
		showStatus('L\'image dépasse 15 Mo.', 'error');
		return;
	}
	showStatus(`Envoi de « ${file.name} »…`);
	try {
		const response = await fetch(`/api/user-images?name=${encodeURIComponent(file.name)}`, {
			method: 'POST',
			headers: { 'Content-Type': file.type || 'application/octet-stream' },
			body: file,
		});
		if (!response.ok) {
			const body = await response.json().catch(() => null);
			showStatus(body?.message ?? `Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		const image = await response.json();
		await refreshLibrary();
		selectUpload(image);
	} catch {
		showStatus(NO_CONNECTION, 'error');
	}
}

async function deleteImage(imageId) {
	const image = library.find((candidate) => candidate.id === imageId);
	if (!image || !window.confirm(`Supprimer « ${image.name} » de la bibliothèque pour tous les joueurs ?\n`
		+ 'Les joueurs qui l\'utilisent retrouveront le fond par défaut.')) {
		return;
	}
	try {
		const response = await fetch(`/api/user-images/${imageId}`, { method: 'DELETE' });
		if (!response.ok && response.status !== 404) {
			showStatus(`Erreur du serveur (HTTP ${response.status}).`, 'error');
			return;
		}
		if (selection?.kind === 'UPLOAD' && selection.imageId === imageId) {
			selection = null;
			editor.hidden = true;
		}
		await refreshLibrary();
		showStatus(`« ${image.name} » supprimée.`);
	} catch {
		showStatus(NO_CONNECTION, 'error');
	}
}

// --- Réglage

/** Identifiant utilisé pour mémoriser le cadrage : l'image importée, ou l'impression et la face Scryfall. */
function imageKey() {
	if (!selection) {
		return null;
	}
	return selection.kind === 'UPLOAD' ? selection.imageId : `${selection.printId}-${selection.face}`;
}

function currentArt() {
	return selection?.kind === 'SCRYFALL' ? selection.arts.find((art) => art.face === selection.face) : null;
}

function framingFor(imageId, layout) {
	return edits[imageId]?.[layout] ?? savedFramings[imageId]?.[layout] ?? { x: 50, y: 50 };
}

function renderEditor() {
	editor.hidden = !selection;
	if (!selection) {
		return;
	}
	for (const button of document.querySelectorAll('#zone-layouts button')) {
		button.setAttribute('aria-pressed', String(Number(button.dataset.layout) === activeLayout));
	}
	const framing = framingFor(imageKey(), activeLayout);
	sliderX.value = String(framing.x);
	sliderY.value = String(framing.y);
	renderFaces();
	renderPreview();
}

function renderFaces() {
	const arts = selection.kind === 'SCRYFALL' ? selection.arts : [];
	faceGroup.hidden = arts.length < 2;
	if (faceGroup.hidden) {
		return;
	}
	faceGroup.replaceChildren(...arts.map((art) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'zone-button';
		button.dataset.face = String(art.face);
		button.setAttribute('aria-pressed', String(art.face === selection.face));
		button.textContent = art.faceName ?? `Face ${art.face + 1}`;
		return button;
	}));
}

function renderPreview() {
	const me = callbacks.getMe();
	const game = callbacks.getGame();
	if (!selection || !me || !game) {
		return;
	}
	const art = currentArt();
	const url = selection.kind === 'UPLOAD' ? selection.url : art?.image;
	const [columns, rows] = LAYOUTS[activeLayout];
	preview.style.setProperty('--ratio', String((16 / columns) / (9 / rows)));
	preview.style.setProperty('--player-color', `var(--player-${me.id})`);
	const corner = infoCorner(activeLayout, me.id);
	preview.dataset.v = corner.v;
	preview.dataset.h = corner.h;
	preview.style.backgroundImage = url ? `url("${url}")` : '';
	preview.style.backgroundPosition = `${sliderX.value}% ${sliderY.value}%`;
	document.getElementById('zone-preview-name').textContent = me.name;
	document.getElementById('zone-preview-life').textContent = String(me.life);
	document.getElementById('zone-preview-credit').textContent =
		artCreditText({ kind: selection.kind, artist: art?.artist });
	const inProgress = activeLayout === game.players.length ? ' (partie en cours)' : '';
	document.getElementById('zone-layout-hint').textContent =
		`Vous réglez la disposition ${activeLayout} joueurs${inProgress}. ${LAYOUT_HINTS[activeLayout]}`;
}

function apply() {
	const me = callbacks.getMe();
	if (!selection || !me) {
		return;
	}
	const message = { type: 'SET_PLAYER_BACKGROUND', player: me.id, kind: selection.kind, framings: edits[imageKey()] ?? {} };
	if (selection.kind === 'UPLOAD') {
		message.imageId = selection.imageId;
	} else {
		message.cardName = selection.cardName;
		message.printId = selection.printId;
		message.face = selection.face;
	}
	callbacks.send(message);
	close();
	callbacks.notify('Illustration envoyée à la table.', 'success');
}

/** Les cadrages mémorisés sont conservés : on pourra reprendre la même image plus tard. */
function clear() {
	const me = callbacks.getMe();
	if (!me) {
		return;
	}
	callbacks.send({ type: 'CLEAR_PLAYER_BACKGROUND', player: me.id });
	close();
	callbacks.notify('Illustration retirée.', 'success');
}

function close() {
	panel.hidden = true;
	document.body.classList.remove('modal-open');
}

async function fetchJson(url) {
	try {
		const response = await fetch(url);
		return response.ok ? await response.json() : null;
	} catch {
		return null;
	}
}

function formatBytes(bytes) {
	return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} Mo` : `${Math.max(1, Math.round(bytes / 1024))} Ko`;
}

function showStatus(text, kind = 'info') {
	statusElement.textContent = text;
	statusElement.dataset.kind = kind;
}
