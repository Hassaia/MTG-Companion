package com.wahib.mtg.mtg_companion.game;

public enum Format {

	STANDARD(20, false),
	COMMANDER(40, true);

	private final int defaultStartingLife;
	private final boolean commanderDamageEnabled;

	Format(int defaultStartingLife, boolean commanderDamageEnabled) {
		this.defaultStartingLife = defaultStartingLife;
		this.commanderDamageEnabled = commanderDamageEnabled;
	}

	public int defaultStartingLife() {
		return defaultStartingLife;
	}

	public boolean commanderDamageEnabled() {
		return commanderDamageEnabled;
	}
}
