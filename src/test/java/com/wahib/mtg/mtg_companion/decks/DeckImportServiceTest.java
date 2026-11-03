package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wahib.mtg.mtg_companion.cards.FakeScryfallServer;
import com.wahib.mtg.mtg_companion.cards.ScryfallClient;
import com.wahib.mtg.mtg_companion.decks.DeckContent.DeckCard;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.Draft;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.DuplicateDeckNameException;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.SaveRequest;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.Status;

import tools.jackson.databind.json.JsonMapper;

/** Import avec un faux Scryfall : {@code POST /cards/collection} répond toujours avec les mêmes cartes. */
class DeckImportServiceTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SHEOLDRED = card("id-sheoldred", "Sheoldred, the Apocalypse", "dmu", "369");
	private static final String SOL_RING = card("id-sol-ring-fdc", "Sol Ring", "fdc", "286");
	private static final String SWAMP = card("id-swamp-hop", "Swamp", "hop", "151");
	private static final String MALAKIR = card("id-malakir", "Malakir Rebirth // Malakir Mire", "znr", "111");
	private static final String DISMEMBER = card("id-dismember-other", "Dismember", "mm3", "99");

	private static final String LIST = """
			1 Sheoldred, the Apocalypse (DMU) 369
			1 Sol Ring (FDC) 286
			27 Swamp (HOP) 151
			1 Malakir Rebirth / Malakir Mire (ZNR) 111
			""";

	@TempDir
	Path directory;

	FakeScryfallServer scryfall;

	@BeforeEach
	void startServer() throws Exception {
		scryfall = new FakeScryfallServer();
		answer(SHEOLDRED, SOL_RING, SWAMP, MALAKIR, DISMEMBER);
	}

	@AfterEach
	void stopServer() {
		scryfall.close();
	}

	private static String card(String id, String name, String set, String number) {
		return "{\"id\":\"%s\",\"name\":\"%s\",\"set\":\"%s\",\"collector_number\":\"%s\"}".formatted(id, name, set, number);
	}

	private void answer(String... cards) {
		scryfall.clearStubs().stubBody("/cards/collection", Map.of(), "{\"object\":\"list\",\"not_found\":[],\"data\":[" + String.join(",", cards) + "]}");
	}

	/** Une instance neuve : seul le disque survit d'une instance à l'autre, comme après un redémarrage. */
	private DeckImportService service() {
		return new DeckImportService(new ScryfallClient(scryfall.baseUrl(), JSON), store());
	}

	private CustomDeckStore store() {
		return new CustomDeckStore(directory.resolve("custom-decks"), JSON);
	}

	private static SaveRequest save(String text, String name, Integer... commanders) {
		return new SaveRequest(text, name, List.of(commanders), null, false);
	}

	@Test
	void previewResolvesExactPrintsAndCountsCards() {
		Draft draft = service().preview(LIST);

		assertThat(draft.totalCards()).isEqualTo(30);
		assertThat(draft.recognizedCards()).isEqualTo(30);
		assertThat(draft.commandersDeclared()).isFalse();
		assertThat(draft.suggestedName()).isEqualTo("Sheoldred, the Apocalypse");
		assertThat(draft.lines()).extracting(DeckImportService.DraftLine::status).containsOnly(Status.OK);
		assertThat(draft.lines().get(0).commander()).isTrue();
		assertThat(draft.lines().get(2).card()).isEqualTo(new DeckCard("Swamp", 27, "id-swamp-hop", "hop", "151"));
	}

	@Test
	void twoFacedCardKeepsItsFullScryfallNameAndPrint() {
		Draft draft = service().preview(LIST);

		assertThat(draft.lines().get(3).card()).isEqualTo(new DeckCard("Malakir Rebirth // Malakir Mire", 1, "id-malakir", "znr", "111"));
	}

	@Test
	void savedDeckSeparatesCommanderFromCardsAndSurvivesRestart() {
		DeckContent saved = service().save(save(LIST, "Mon Sheoldred", 0));

		assertThat(saved.id()).startsWith("custom_");
		assertThat(DeckService.isValidId(saved.id())).isTrue();
		assertThat(saved.origin()).isEqualTo(DeckOrigin.CUSTOM);
		assertThat(saved.commanders()).extracting(DeckCard::name).containsExactly("Sheoldred, the Apocalypse");
		assertThat(saved.cards()).extracting(DeckCard::name).containsExactly("Malakir Rebirth // Malakir Mire", "Sol Ring", "Swamp");
		assertThat(saved.cards().stream().mapToInt(DeckCard::count).sum()).isEqualTo(29);

		DeckContent reloaded = store().get(saved.id()).orElseThrow();
		assertThat(reloaded).isEqualTo(saved);
	}

	@Test
	void sameCardInTwoPrintsStaysOnTwoLines() {
		answer(SOL_RING, card("id-sol-ring-c21", "Sol Ring", "c21", "263"));

		DeckContent saved = service().save(save("1 Sol Ring (FDC) 286\n1 Sol Ring (C21) 263\n1 Sol Ring (FDC) 286", "Anneaux"));

		assertThat(saved.cards()).extracting(DeckCard::printId, DeckCard::count)
				.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("id-sol-ring-fdc", 2),
						org.assertj.core.groups.Tuple.tuple("id-sol-ring-c21", 1));
	}

	@Test
	void unknownCardIsReportedAndNeverReplaced() {
		Draft draft = service().preview("1 Sol Ring (FDC) 286\n1 Carte Inexistante (XXX) 1\n2 Autre Inconnue");

		assertThat(draft.lines().get(1).status()).isEqualTo(Status.NOT_FOUND);
		assertThat(draft.lines().get(1).card()).isNull();
		assertThat(draft.lines().get(2).status()).isEqualTo(Status.NOT_FOUND);
		assertThat(draft.totalCards()).isEqualTo(4);
		assertThat(draft.recognizedCards()).isEqualTo(1);
	}

	@Test
	void savingWithUnknownCardsIsRefusedUnlessTheyAreExplicitlyDropped() {
		String list = "1 Sol Ring (FDC) 286\n1 Carte Inexistante (XXX) 1";

		assertThatThrownBy(() -> service().save(save(list, "Deck")))
				.isInstanceOf(DeckImportService.DeckImportException.class);
		assertThat(store().all()).isEmpty();

		DeckContent saved = service().save(new SaveRequest(list, "Deck", List.of(), null, true));
		assertThat(saved.cards()).extracting(DeckCard::name).containsExactly("Sol Ring");
	}

	@Test
	void unknownPrintFallsBackToExactNameWithoutPrintInsteadOfAnotherPrint() {
		// Dismember n'existe pas en (NPH) 57 dans la réponse : la carte est reconnue par son nom, sans impression
		Draft draft = service().preview("1 Dismember (NPH) 57");

		assertThat(draft.lines().get(0).status()).isEqualTo(Status.NAME_ONLY);
		assertThat(draft.lines().get(0).card()).isEqualTo(new DeckCard("Dismember", 1, null, null, null));
	}

	@Test
	void manualCommanderSelectionIsKept() {
		DeckContent saved = service().save(save(LIST, "Choix manuel", 1));

		assertThat(saved.commanders()).extracting(DeckCard::name).containsExactly("Sol Ring");
		assertThat(saved.cards()).extracting(DeckCard::name).contains("Sheoldred, the Apocalypse");
	}

	@Test
	void duplicateNameIsRefusedThenAllowedAsUpdateOrRename() {
		DeckImportService service = service();
		DeckContent first = service.save(save(LIST, "Sheoldred", 0));

		assertThatThrownBy(() -> service.save(save(LIST, "  sheoldred ", 0))).isInstanceOf(DuplicateDeckNameException.class)
				.satisfies(e -> assertThat(((DuplicateDeckNameException) e).existingId()).isEqualTo(first.id()));

		DeckContent renamed = service.save(save(LIST, "Sheoldred v2", 0));
		assertThat(renamed.id()).isNotEqualTo(first.id());

		answer(SHEOLDRED, SOL_RING);
		DeckContent updated = service.save(new SaveRequest("1 Sheoldred, the Apocalypse (DMU) 369\n1 Sol Ring (FDC) 286",
				"Sheoldred", List.of(0), first.id(), false));
		assertThat(updated.id()).isEqualTo(first.id());
		assertThat(updated.cards()).extracting(DeckCard::name).containsExactly("Sol Ring");
		assertThat(store().all()).hasSize(2);
	}

	@Test
	void renameKeepsTheIdAndRefusesAnExistingName() {
		DeckImportService service = service();
		DeckContent first = service.save(save(LIST, "Premier", 0));
		service.save(save(LIST, "Second", 0));

		DeckContent renamed = service.rename(first.id(), "Un nom libre — avec accents & symboles");

		assertThat(renamed.id()).isEqualTo(first.id());
		assertThat(store().get(first.id()).orElseThrow().name()).isEqualTo("Un nom libre — avec accents & symboles");
		assertThatThrownBy(() -> service.rename(first.id(), "SECOND")).isInstanceOf(DuplicateDeckNameException.class);
		assertThatThrownBy(() -> service.rename(first.id(), "  ")).isInstanceOf(DeckImportService.DeckImportException.class);
	}

	@Test
	void cardsAreResolvedInBatchesOfAtMost75() {
		StringBuilder list = new StringBuilder();
		for (int i = 1; i <= 100; i++) {
			list.append("1 Carte ").append(i).append(" (TST) ").append(i).append('\n');
		}

		service().preview(list.toString());

		long requests = scryfall.requests().stream().filter(request -> request.path().equals("/cards/collection")).count();
		// 100 impressions -> 2 lots ; puis le repli par nom des 100 cartes non trouvées -> 2 lots
		assertThat(requests).isEqualTo(4);
	}
}
