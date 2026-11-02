package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wahib.mtg.mtg_companion.cards.Card.CardFace;
import com.wahib.mtg.mtg_companion.cards.Card.CardImage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** S'appuie sur de vraies réponses Scryfall, avec leurs imperfections. */
class ScryfallCardMapperTest {

	private static final String LIGHTNING_BOLT_FR_MSC = "1f93e813-516d-43c6-a2f3-4196c18620a5";

	@Test
	void frenchCardUsesFrenchTextAndNewestFrenchImage() {
		Card card = ScryfallCardMapper.toCard(ScryfallFixtures.json("named-lightning-bolt.json"),
				ScryfallFixtures.data("fr-prints-lightning-bolt.json"));

		assertThat(card.name()).isEqualTo("Lightning Bolt");
		assertThat(card.lang()).isEqualTo("fr");
		CardFace face = card.faces().getFirst();
		assertThat(face.name()).isEqualTo("Foudre");
		assertThat(face.text()).startsWith("La Foudre inflige 3 blessures");
		assertThat(card.images()).extracting(CardImage::fileName).containsExactly(LIGHTNING_BOLT_FR_MSC + "-0.jpg");
		assertThat(card.images().getFirst().sourceUrl()).contains("/large/");
	}

	@Test
	void missingFrenchFieldIsTakenFromAnOlderFrenchPrint() {
		// Les deux impressions françaises les plus récentes n'ont pas de printed_type_line
		Card card = ScryfallCardMapper.toCard(ScryfallFixtures.json("named-lightning-bolt.json"),
				ScryfallFixtures.data("fr-prints-lightning-bolt.json"));

		assertThat(card.faces().getFirst().typeLine()).isEqualTo("Éphémère");
	}

	@Test
	void doubleFacedCardHasTwoFacesAndTwoImages() {
		Card card = ScryfallCardMapper.toCard(ScryfallFixtures.json("named-delver-of-secrets.json"),
				ScryfallFixtures.data("fr-prints-delver-of-secrets.json"));

		// L'impression la plus récente garde le nom anglais : on préfère une vraie traduction
		assertThat(card.faces()).extracting(CardFace::name)
				.containsExactly("Sondeur de secrets", "Aberration insectoïde");
		assertThat(card.images()).extracting(CardImage::fileName).containsExactly(
				"1bcae0cc-ff55-43e1-bbf7-063a24339712-0.jpg",
				"1bcae0cc-ff55-43e1-bbf7-063a24339712-1.jpg");
	}

	@Test
	void adventureCardHasTwoTextFacesButASingleImage() {
		Card card = ScryfallCardMapper.toCard(ScryfallFixtures.json("named-bonecrusher-giant.json"),
				ScryfallFixtures.data("fr-prints-bonecrusher-giant.json"));

		assertThat(card.faces()).extracting(CardFace::name).containsExactly("Géant craque-os", "Piétineur");
		assertThat(card.images()).hasSize(1);
	}

	@Test
	void cardWithoutFrenchPrintFallsBackToEnglish() {
		JsonNode english = ScryfallFixtures.json("named-the-fourteenth-doctor.json");

		Card card = ScryfallCardMapper.toCard(english, List.of());

		assertThat(card.lang()).isEqualTo("en");
		assertThat(card.faces().getFirst().name()).isEqualTo("The Fourteenth Doctor");
		assertThat(card.faces().getFirst().typeLine()).isEqualTo(english.get("type_line").asString());
		assertThat(card.faces().getFirst().text()).isEqualTo(english.get("oracle_text").asString());
		assertThat(card.images()).extracting(CardImage::fileName)
				.containsExactly(english.get("id").asString() + "-0.jpg");
	}

	@Test
	void placeholderFrenchImageIsReplacedByEnglishImageButTextStaysFrench() {
		JsonNode english = ScryfallFixtures.json("named-lightning-bolt.json");
		List<JsonNode> frenchPrints = ScryfallFixtures.data("fr-prints-lightning-bolt.json");
		frenchPrints.forEach(print -> ((ObjectNode) print).put("image_status", "placeholder"));

		Card card = ScryfallCardMapper.toCard(english, frenchPrints);

		assertThat(card.faces().getFirst().name()).isEqualTo("Foudre");
		assertThat(card.images()).extracting(CardImage::fileName)
				.containsExactly(english.get("id").asString() + "-0.jpg");
	}

	@Test
	void missingFrenchTextFallsBackToEnglishOracleText() {
		JsonNode english = ScryfallFixtures.json("named-lightning-bolt.json");
		List<JsonNode> frenchPrints = ScryfallFixtures.data("fr-prints-lightning-bolt.json");
		frenchPrints.forEach(print -> ((ObjectNode) print).remove("printed_text"));

		Card card = ScryfallCardMapper.toCard(english, frenchPrints);

		assertThat(card.faces().getFirst().name()).isEqualTo("Foudre");
		assertThat(card.faces().getFirst().text()).isEqualTo(english.get("oracle_text").asString());
	}

	@Test
	void illustrationsPreferFrenchPrintsAndListThemFirst() {
		List<CardIllustrations.Illustration> illustrations = ScryfallCardMapper.illustrations(
				ScryfallFixtures.json("named-lightning-bolt.json"),
				ScryfallFixtures.data("arts-lightning-bolt.json"),
				ScryfallFixtures.data("fr-prints-lightning-bolt.json"));

		// 4 illustrations existent en français (de la plus récente à la plus ancienne), 2 seulement en anglais
		assertThat(illustrations).extracting(CardIllustrations.Illustration::printId).containsExactly(
				LIGHTNING_BOLT_FR_MSC,
				"58a21915-1e4d-4da3-82f4-0e55178b7b82",
				"a2f87032-f9b0-41e8-bfac-35e0c6ce0af1",
				"1ac99521-7dba-4d53-b125-17f037180d09",
				"bba5ea18-4e58-42a8-b613-2178706476a6",
				"0ef3c273-ec92-41d0-9abe-0f99e33db4f5");
		assertThat(illustrations).extracting(CardIllustrations.Illustration::lang)
				.containsExactly("fr", "fr", "fr", "fr", "en", "en");
	}

	@Test
	void illustrationCarriesItsEditionThumbnailAndLargeImage() {
		List<CardIllustrations.Illustration> illustrations = ScryfallCardMapper.illustrations(
				ScryfallFixtures.json("named-lightning-bolt.json"),
				ScryfallFixtures.data("arts-lightning-bolt.json"),
				ScryfallFixtures.data("fr-prints-lightning-bolt.json"));

		CardIllustrations.Illustration first = illustrations.getFirst();
		assertThat(first.collectorNumber()).isEqualTo("806");
		assertThat(first.setName()).isNotBlank();
		assertThat(first.images()).extracting(CardImage::fileName).containsExactly(LIGHTNING_BOLT_FR_MSC + "-0.jpg");
		assertThat(illustrations.getLast().thumbnailUrl()).contains("/small/");
	}

	@Test
	void doubleFacedIllustrationsHaveBothFacesAndAFrontThumbnail() {
		List<CardIllustrations.Illustration> illustrations = ScryfallCardMapper.illustrations(
				ScryfallFixtures.json("named-delver-of-secrets.json"),
				ScryfallFixtures.data("arts-delver-of-secrets.json"),
				ScryfallFixtures.data("fr-prints-delver-of-secrets.json"));

		assertThat(illustrations).extracting(CardIllustrations.Illustration::printId).containsExactly(
				"1bcae0cc-ff55-43e1-bbf7-063a24339712",
				"3a486708-36b6-48b4-bf25-3b0340a1a04c",
				"a808459c-f086-4cb6-a53e-4b9e196c1000");
		assertThat(illustrations).allSatisfy(illustration -> {
			assertThat(illustration.images()).hasSize(2);
			assertThat(illustration.thumbnailUrl()).contains("/front/");
		});
	}

	@Test
	void withoutIllustrationListTheDefaultEnglishPrintIsTheOnlyChoice() {
		JsonNode english = ScryfallFixtures.json("named-the-fourteenth-doctor.json");

		List<CardIllustrations.Illustration> illustrations = ScryfallCardMapper.illustrations(english, List.of(), List.of());

		assertThat(illustrations).extracting(CardIllustrations.Illustration::printId)
				.containsExactly(english.get("id").asString());
	}

	@Test
	void localizedNameOfASearchResult() {
		List<JsonNode> results = ScryfallFixtures.data("search-fr-foud.json");

		assertThat(results.stream().map(ScryfallCardMapper::localizedName).toList()).contains(
				new CardSuggestions.LocalizedName("Blaster Hulk", "Carcasse à foudroyeur"),
				new CardSuggestions.LocalizedName("Invasion of Tarkir // Defiant Thundermaw",
						"Invasion de Tarkir // Foudregueule provocateur"));
	}

	@Test
	void illustrationCarriesItsArtCropAndArtist() {
		JsonNode print = ScryfallFixtures.data("arts-lightning-bolt.json").getFirst();

		CardIllustrations.Illustration illustration = ScryfallCardMapper.illustration(print).orElseThrow();

		assertThat(illustration.arts()).hasSize(1);
		CardIllustrations.Art art = illustration.arts().getFirst();
		assertThat(art.face()).isZero();
		assertThat(art.artist()).isEqualTo(print.get("artist").asString());
		assertThat(art.sourceUrl()).contains("/art_crop/");
		assertThat(illustration.hasArt()).isTrue();
	}

	@Test
	void doubleFacedPrintHasOneArtPerFaceWithTheArtistOfEachFace() {
		JsonNode print = ScryfallFixtures.JSON.readTree("""
				{"id":"1bcae0cc-ff55-43e1-bbf7-063a24339712","name":"Delver // Insectile","lang":"en",
				 "image_status":"highres_scan","artist":"Florey & Scott Okumura",
				 "card_faces":[
				  {"name":"Delver","artist":"Florey","image_uris":{"large":"https://x/large/0.jpg","art_crop":"https://x/art_crop/0.jpg"}},
				  {"name":"Insectile","image_uris":{"large":"https://x/large/1.jpg","art_crop":"https://x/art_crop/1.jpg"}}]}""");

		List<CardIllustrations.Art> arts = ScryfallCardMapper.illustration(print).orElseThrow().arts();

		assertThat(arts).extracting(CardIllustrations.Art::face).containsExactly(0, 1);
		assertThat(arts).extracting(CardIllustrations.Art::faceName).containsExactly("Delver", "Insectile");
		// La face sans artiste propre prend celui de la carte ; rien n'est inventé
		assertThat(arts).extracting(CardIllustrations.Art::artist).containsExactly("Florey", "Florey & Scott Okumura");
	}

	@Test
	void adventureCardHasASingleArtFromTheRoot() {
		JsonNode print = ScryfallFixtures.JSON.readTree("""
				{"id":"1bcae0cc-ff55-43e1-bbf7-063a24339712","name":"Bonecrusher Giant // Stomp","lang":"en",
				 "image_status":"highres_scan","artist":"Kev Walker",
				 "image_uris":{"large":"https://x/large/0.jpg","art_crop":"https://x/art_crop/0.jpg"},
				 "card_faces":[{"name":"Bonecrusher Giant"},{"name":"Stomp"}]}""");

		assertThat(ScryfallCardMapper.illustration(print).orElseThrow().arts())
				.extracting(CardIllustrations.Art::face).containsExactly(0);
	}

	@Test
	void printWithoutArtCropOrWithAPlaceholderImageHasNoArt() {
		JsonNode withoutArtCrop = ScryfallFixtures.JSON.readTree("""
				{"id":"1bcae0cc-ff55-43e1-bbf7-063a24339712","name":"X","lang":"en","image_status":"highres_scan",
				 "image_uris":{"large":"https://x/large/0.jpg"}}""");
		JsonNode placeholder = ScryfallFixtures.JSON.readTree("""
				{"id":"1bcae0cc-ff55-43e1-bbf7-063a24339712","name":"X","lang":"en","image_status":"placeholder",
				 "image_uris":{"large":"https://x/large/0.jpg","art_crop":"https://x/art_crop/0.jpg"}}""");

		assertThat(ScryfallCardMapper.illustration(withoutArtCrop).orElseThrow().arts()).isEmpty();
		assertThat(ScryfallCardMapper.illustration(placeholder).orElseThrow().hasArt()).isFalse();
	}
}
