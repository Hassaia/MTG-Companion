package com.wahib.mtg.mtg_companion.userimages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wahib.mtg.mtg_companion.game.InvalidActionException;

import tools.jackson.databind.json.JsonMapper;

class PlayerArtServiceTest {

	private static final String SCRYFALL_IMAGE = "bba5ea18-4e58-42a8-b613-2178706476a6-0";

	@TempDir
	Path directory;

	/** Chaque appel recrée le service sur les mêmes fichiers : c'est un redémarrage du serveur. */
	private PlayerArtService service() {
		return new PlayerArtService(new PlayerArtStore(directory.resolve("art.json"), JsonMapper.builder().build()),
				directory.resolve("images"));
	}

	@Test
	void validImageIsStoredUntouchedAndListed() throws Exception {
		byte[] original = ImageFormatTest.png(3000, 2000);

		UserImage image = service().add("Ma photo.png", original);

		assertThat(image.name()).isEqualTo("Ma photo.png");
		assertThat(image.contentType()).isEqualTo("image/png");
		assertThat(image.width()).isEqualTo(3000);
		assertThat(image.height()).isEqualTo(2000);
		assertThat(image.bytes()).isEqualTo(original.length);
		assertThat(directory.resolve("images").resolve(image.id() + ".png")).hasBinaryContent(original);
	}

	@Test
	void libraryAndFileSurviveARestart() throws Exception {
		UserImage image = service().add("a.jpg", ImageFormatTest.jpeg(800, 600));

		PlayerArtService restarted = service();

		assertThat(restarted.list()).containsExactly(image);
		assertThat(restarted.file(image.id())).isPresent();
	}

	@Test
	void imageIdIsGeneratedAndNeverCollides() {
		PlayerArtService service = service();

		UserImage first = service.add("same.png", ImageFormatTest.png(10, 10));
		UserImage second = service.add("same.png", ImageFormatTest.png(10, 10));

		assertThat(first.id()).isNotEqualTo(second.id());
		assertThat(PlayerArtService.isValidImageId(first.id())).isTrue();
		assertThat(service.list()).hasSize(2);
	}

	@Test
	void hostileNameNeverReachesThePath() throws Exception {
		UserImage image = service().add("../../etc/passwd\u0000.png", ImageFormatTest.png(10, 10));

		assertThat(image.name()).isEqualTo("passwd.png");
		try (var files = Files.list(directory.resolve("images"))) {
			assertThat(files).allSatisfy(file -> assertThat(file.getFileName().toString())
					.isEqualTo(image.id() + ".png"));
		}
		assertThat(PlayerArtService.cleanName("   ")).isEqualTo("Image");
		assertThat(PlayerArtService.cleanName("x".repeat(500))).hasSize(80);
	}

	@Test
	void rejectsUnsupportedEmptyAndTooLargeFiles() {
		PlayerArtService service = service();

		assertThatThrownBy(() -> service.add("a.svg", "<svg/>".getBytes()))
				.isInstanceOfSatisfying(UserImageException.class, e -> assertThat(e.httpStatus()).isEqualTo(415));
		assertThatThrownBy(() -> service.add("a.png", new byte[0]))
				.isInstanceOfSatisfying(UserImageException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
		byte[] tooLarge = Arrays.copyOf(ImageFormatTest.png(10, 10), (int) PlayerArtService.MAX_BYTES + 1);
		assertThatThrownBy(() -> service.add("a.png", tooLarge))
				.isInstanceOfSatisfying(UserImageException.class, e -> assertThat(e.httpStatus()).isEqualTo(413));
		assertThat(service.list()).isEmpty();
		assertThat(directory.resolve("images")).doesNotExist();
	}

	@Test
	void deleteRemovesTheFileTheMetadataAndEveryPlayersFraming() throws Exception {
		PlayerArtService service = service();
		UserImage image = service.add("a.png", ImageFormatTest.png(10, 10));
		service.setFraming(1, image.id(), 2, new Framing(10, 20));
		service.setFraming(3, image.id(), 4, new Framing(30, 40));
		service.setFraming(1, SCRYFALL_IMAGE, 2, new Framing(60, 70));

		assertThat(service.delete(image.id())).isTrue();

		assertThat(service.delete(image.id())).isFalse();
		assertThat(directory.resolve("images").resolve(image.id() + ".png")).doesNotExist();
		PlayerArtService restarted = service();
		assertThat(restarted.list()).isEmpty();
		assertThat(restarted.framingsOf(1)).containsOnlyKeys(SCRYFALL_IMAGE);
		assertThat(restarted.framingsOf(3)).isEmpty();
	}

	@Test
	void framingDefaultsToTheCenter() {
		assertThat(service().framing(1, SCRYFALL_IMAGE, 3)).isEqualTo(new Framing(50, 50));
	}

	@Test
	void framingIsIndependentPerPlayerImageAndLayoutAndSurvivesARestart() {
		PlayerArtService service = service();
		String upload = service.add("a.png", ImageFormatTest.png(10, 10)).id();
		service.setFraming(1, SCRYFALL_IMAGE, 2, new Framing(10, 11));
		service.setFraming(1, SCRYFALL_IMAGE, 3, new Framing(20, 21));
		service.setFraming(1, SCRYFALL_IMAGE, 4, new Framing(30, 31));
		service.setFraming(2, SCRYFALL_IMAGE, 3, new Framing(90, 91));
		service.setFraming(1, upload, 3, new Framing(70, 71));

		PlayerArtService restarted = service();

		assertThat(restarted.framing(1, SCRYFALL_IMAGE, 2)).isEqualTo(new Framing(10, 11));
		assertThat(restarted.framing(1, SCRYFALL_IMAGE, 3)).isEqualTo(new Framing(20, 21));
		assertThat(restarted.framing(1, SCRYFALL_IMAGE, 4)).isEqualTo(new Framing(30, 31));
		assertThat(restarted.framing(2, SCRYFALL_IMAGE, 3)).isEqualTo(new Framing(90, 91));
		assertThat(restarted.framing(2, SCRYFALL_IMAGE, 2)).isEqualTo(Framing.CENTERED);
		assertThat(restarted.framing(1, upload, 3)).isEqualTo(new Framing(70, 71));
		assertThat(restarted.framing(1, upload, 2)).isEqualTo(Framing.CENTERED);
	}

	@Test
	void seedingNeverOverwritesAnExistingFraming() {
		PlayerArtService service = service();

		service.seedFraming(1, SCRYFALL_IMAGE, 4, new Framing(30, 40));
		service.seedFraming(1, SCRYFALL_IMAGE, 4, new Framing(99, 99));

		assertThat(service.framing(1, SCRYFALL_IMAGE, 4)).isEqualTo(new Framing(30, 40));
	}

	@Test
	void rejectsInvalidFramingKeysAndValues() {
		PlayerArtService service = service();

		assertThatThrownBy(() -> service.setFraming(1, SCRYFALL_IMAGE, 5, new Framing(1, 1)))
				.isInstanceOf(InvalidActionException.class);
		assertThatThrownBy(() -> service.setFraming(1, "../x", 2, new Framing(1, 1)))
				.isInstanceOf(InvalidActionException.class);
		assertThatThrownBy(() -> new Framing(101, 0)).isInstanceOf(InvalidActionException.class);
		assertThatThrownBy(() -> new Framing(0, -1)).isInstanceOf(InvalidActionException.class);
	}

	@Test
	void corruptedFileStartsAnEmptyLibrary() throws Exception {
		Files.writeString(directory.resolve("art.json"), "{ pas du json");

		assertThat(service().list()).isEmpty();
	}
}
