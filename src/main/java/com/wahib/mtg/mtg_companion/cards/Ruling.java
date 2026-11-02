package com.wahib.mtg.mtg_companion.cards;

/**
 * Clarification de règle publiée sur Scryfall, dans sa langue d'origine (anglais).
 *
 * @param source      {@code wotc} ou {@code scryfall}
 * @param publishedAt date {@code AAAA-MM-JJ}
 */
public record Ruling(String source, String publishedAt, String comment) {
}
