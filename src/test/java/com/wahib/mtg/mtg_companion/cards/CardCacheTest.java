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

	@Test
	void savedIllustrationsAreFoundByCardName() throws Exception {
		CardIllustrations illustrations = new CardIllustrations("Lightning Bolt", List.of(
				new CardIllustrations.Illustration("1f93e813-516d-43c6-a2f3-4196c18620a5", "fr", "Secret Lair", "806",
						"2026-06-26", "https://cards.scryfall.io/small/x.jpg", BOLT.images())));

		cache().saveIllustrations(illustrations);

		assertThat(cache().findIllustrations("LIGHTNING BOLT")).contains(illustrations);
		assertThat(cache().findIllustrations("Fire // Ice")).isEmpty();
	}

	@Test
	void savedPrintIsFoundById() throws Exception {
		CardIllustrations.Illustration print = new CardIllustrations.Illustration("bc7a51db-3a36-4387-bdd5-12c05f1cd856",
				"fr", "Final Fantasy Commander", "168", "2025-06-13", null, BOLT.images());

		cache().savePrint("Cloud, Ex-SOLDIER", print);

		assertThat(cache().findPrint("cloud, ex-soldier", "bc7a51db-3a36-4387-bdd5-12c05f1cd856")).contains(print);
		assertThat(cache().findPrint("Cloud, Ex-SOLDIER", "../cards")).isEmpty();
	}

	@Test
	void printIsNeverReturnedForAnotherCard() throws Exception {
		CardIllustrations.Illustration print = new CardIllustrations.Illustration("bc7a51db-3a36-4387-bdd5-12c05f1cd856",
				"fr", "Final Fantasy Commander", "168", "2025-06-13", null, BOLT.images());
		cache().savePrint("Cloud, Ex-SOLDIER", print);

		assertThat(cache().findPrint("Lightning Bolt", "bc7a51db-3a36-4387-bdd5-12c05f1cd856")).isEmpty();
	}

	@Test
	void savesAndFindsThumbnails() throws Exception {
		String printId = "1f93e813-516d-43c6-a2f3-4196c18620a5";
		assertThat(cache().thumbnailPath(printId)).isEmpty();

		cache().saveThumbnail(printId, new byte[] {4, 5});

		assertThat(cache().thumbnailPath(printId)).hasValueSatisfying(
				path -> assertThat(path).hasBinaryContent(new byte[] {4, 5}));
	}

	@ParameterizedTest
	@ValueSource(strings = {"../cards", "1F93E813-516D-43C6-A2F3-4196C18620A5", "abc", ""})
	void rejectsInvalidPrintIds(String printId) {
		assertThat(CardCache.isValidPrintId(printId)).isFalse();
		assertThat(cache().thumbnailPath(printId)).isEmpty();
		assertThatThrownBy(() -> cache().saveThumbnail(printId, new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"../mtg-game-state.json", "..\\x.jpg", "abc.jpg", "1f93e813-516d-43c6-a2f3-4196c18620a5-0.png", ""})
	void rejectsInvalidImageNames(String fileName) {
		assertThat(CardCache.isValidImageFileName(fileName)).isFalse();
		assertThat(cache().imagePath(fileName)).isEmpty();
		assertThatThrownBy(() -> cache().saveImage(fileName, new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void savedArtIsFoundByItsFileNameOnly() throws Exception {
		cache().saveArt(IMAGE, new byte[] {4, 5});

		assertThat(cache().artPath(IMAGE)).hasValueSatisfying(path -> assertThat(path).hasBinaryContent(new byte[] {4, 5}));
		assertThat(cache().imagePath(IMAGE)).isEmpty();
		assertThat(cache().artPath("../state.json")).isEmpty();
		assertThatThrownBy(() -> cache().saveArt("../x.jpg", new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void illustrationWrittenBeforeArtsExistedKeepsThemUnknown() throws Exception {
		cache().saveIllustrations(new CardIllustrations("Lightning Bolt", List.of(
				new CardIllustrations.Illustration("1f93e813-516d-43c6-a2f3-4196c18620a5", "fr", null, null, null, null,
						BOLT.images()))));

		assertThat(cache().findIllustrations("Lightning Bolt").orElseThrow().illustrations().getFirst().arts()).isNull();
	}
}
