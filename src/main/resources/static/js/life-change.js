// Variation récente des PV (« -3 ») : calculée côté client en comparant les états successifs.
// Les modifications rapprochées se cumulent ; l'indication disparaît après un moment sans changement.

const VISIBLE_DURATION_MS = 2000;

export class LifeChangeIndicator {

	/** @param {HTMLElement} element élément .life-change à remplir */
	constructor(element) {
		this.element = element;
		this.previousLife = null;
		this.accumulated = 0;
		this.hideTimer = null;
	}

	/** À appeler à chaque nouvel état ; le premier appel sert seulement de point de départ. */
	update(life) {
		if (this.previousLife !== null && life !== this.previousLife) {
			this.accumulated += life - this.previousLife;
			this.show();
		}
		this.previousLife = life;
	}

	show() {
		clearTimeout(this.hideTimer);
		if (this.accumulated === 0) {
			// Par exemple -1 puis +1 : il n'y a plus rien à signaler
			this.hide();
			return;
		}
		const sign = this.accumulated > 0 ? '+' : '−';
		this.element.textContent = `${sign}${Math.abs(this.accumulated)}`;
		this.element.dataset.sign = this.accumulated > 0 ? 'positive' : 'negative';
		this.element.classList.add('visible');
		this.hideTimer = setTimeout(() => this.hide(), VISIBLE_DURATION_MS);
	}

	hide() {
		this.element.classList.remove('visible');
		this.accumulated = 0;
	}
}
