package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.wahib.mtg.mtg_companion.cards.Card.CardFace;
import com.wahib.mtg.mtg_companion.cards.Card.CardImage;

class CardCacheTest {

	private static final String IMAGE = "1f93e813-516d-43c6-a2f3-4196c18620a5-0.jpg";
	private static final Card BOLT = new Card("Lightning Bolt", "fr",
			List.of(new CardFace("Foudre", "Éphémère", "La Foudre inflige 3 blessures à n'importe quelle cible.")),
			List.of(new CardImage(IMAGE, "https://cards.scryfall.io/large/front/1/f/x.jpg")));

	@TempDir
	Path directory;

	private CardCache cache() {
		return new CardCache(directory, ScryfallFixtures.JSON);
	}

	@Test
	void savedCardIsFoundByItsNameIgnoringCase() throws Exception {
		cache().save(BOLT);

		assertThat(cache().find("lightning bolt")).contains(BOLT);
	}

	@Test
	void unknownCardIsEmpty() {
		assertThat(cache().find("Lightning Bolt")).isEmpty();
	}

	@Test
	void namesWithSpecialCharactersAreStoredSafely() throws Exception {
		Card card = new Card("Fire // Ice", "en", List.of(), List.of());

		cache().save(card);

		assertThat(cache().find("Fire // Ice")).contains(card);
	}

	@Test
	void listsAllCachedCards() throws Exception {
		cache().save(BOLT);
		cache().save(new Card("Fire // Ice", "en", List.of(), List.of()));

		assertThat(cache().all()).extracting(Card::name).containsExactlyInAnyOrder("Lightning Bolt", "Fire // Ice");
	}

	@Test
	void corruptedCardFileIsIgnored() throws Exception {
		cache().save(BOLT);
		try (var files = Files.list(directory.resolve("cards"))) {
			Files.writeString(files.findFirst().orElseThrow(), "{ pas du json");
		}

		assertThat(cache().find("Lightning Bolt")).isEmpty();
		assertThat(cache().all()).isEmpty();
	}

	@Test
	void savesAndFindsImages() throws Exception {
		assertThat(cache().hasImage(IMAGE)).isFalse();

		cache().saveImage(IMAGE, new byte[] {1, 2, 3});

		assertThat(cache().imagePath(IMAGE)).hasValueSatisfying(
				path -> assertThat(path).hasBinaryContent(new byte[] {1, 2, 3}));
	}

	@ParameterizedTest
	@ValueSource(strings = {"../mtg-game-state.json", "..\\x.jpg", "abc.jpg", "1f93e813-516d-43c6-a2f3-4196c18620a5-0.png", ""})
	void rejectsInvalidImageNames(String fileName) {
		assertThat(CardCache.isValidImageFileName(fileName)).isFalse();
		assertThat(cache().imagePath(fileName)).isEmpty();
		assertThatThrownBy(() -> cache().saveImage(fileName, new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}
}
