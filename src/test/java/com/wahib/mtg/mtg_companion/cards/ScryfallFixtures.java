package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Vraies réponses Scryfall enregistrées (allégées) dans src/test/resources/scryfall. */
final class ScryfallFixtures {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private ScryfallFixtures() {
	}

	static String text(String fileName) {
		try (InputStream input = ScryfallFixtures.class.getResourceAsStream("/scryfall/" + fileName)) {
			if (input == null) {
				throw new IllegalArgumentException("Fixture absente : " + fileName);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static JsonNode json(String fileName) {
		return JSON.readTree(text(fileName));
	}

	/** Le tableau {@code data} d'une liste Scryfall. */
	static List<JsonNode> data(String fileName) {
		JsonNode data = json(fileName).get("data");
		List<JsonNode> result = new ArrayList<>();
		for (int index = 0; index < data.size(); index++) {
			result.add(data.get(index));
		}
		return result;
	}
}
