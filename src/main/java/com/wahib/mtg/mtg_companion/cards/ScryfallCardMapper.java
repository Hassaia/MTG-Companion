package com.wahib.mtg.mtg_companion.cards;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.wahib.mtg.mtg_companion.cards.Card.CardFace;
import com.wahib.mtg.mtg_companion.cards.Card.CardImage;

import tools.jackson.databind.JsonNode;

/**
 * Transforme les réponses Scryfall en {@link Card} à afficher.
 * <p>
 * Les données françaises de Scryfall sont incomplètes et varient d'une impression à l'autre
 * (type absent, nom laissé en anglais…). Chaque champ de chaque face est donc cherché dans toutes
 * les impressions françaises, en préférant une valeur réellement traduite, avant de revenir au
 * texte anglais.
 */
public final class ScryfallCardMapper {

	/** Statuts d'une vraie photo de la carte ; « placeholder » et « missing » n'en sont pas. */
	private static final Set<String> REAL_IMAGE_STATUSES = Set.of("highres_scan", "lowres");

	private ScryfallCardMapper() {
	}

	/**
	 * @param englishCard  version anglaise par défaut (réponse de /cards/named)
	 * @param frenchPrints impressions françaises, de la plus récente à la plus ancienne (peut être vide)
	 */
	public static Card toCard(JsonNode englishCard, List<JsonNode> frenchPrints) {
		// Les impressions numériques (Arena, MTGO) passent après les cartes papier ; le tri est stable
		List<JsonNode> french = frenchPrints.stream()
				.sorted(Comparator.comparing((JsonNode print) -> print.path("digital").asBoolean(false)))
				.toList();

		List<CardFace> faces = new ArrayList<>();
		int faceCount = Math.max(1, faces(englishCard).size());
		for (int index = 0; index < faceCount; index++) {
			int faceIndex = index;
			JsonNode englishFace = face(englishCard, faceIndex);
			List<JsonNode> frenchFaces = french.stream()
					.map(print -> face(print, faceIndex))
					.filter(Objects::nonNull)
					.toList();
			faces.add(new CardFace(
					preferTranslated(frenchFaces, "printed_name", text(englishFace, "name")),
					preferTranslated(frenchFaces, "printed_type_line", text(englishFace, "type_line")),
					preferTranslated(frenchFaces, "printed_text", text(englishFace, "oracle_text"))));
		}

		JsonNode imagePrint = french.stream().filter(ScryfallCardMapper::hasRealImage).findFirst().orElse(englishCard);
		return new Card(text(englishCard, "name"), french.isEmpty() ? "en" : "fr", faces, images(imagePrint));
	}

	/** Nom anglais et nom français d'une carte trouvée par une recherche en français, pour les suggestions. */
	public static CardSuggestions.LocalizedName localizedName(JsonNode frenchCard) {
		String englishName = text(frenchCard, "name");
		String localName = text(frenchCard, "printed_name");
		if (localName == null && !faces(frenchCard).isEmpty()) {
			List<String> faceNames = new ArrayList<>();
			for (JsonNode face : faces(frenchCard)) {
				String faceName = text(face, "printed_name");
				faceNames.add(faceName != null ? faceName : text(face, "name"));
			}
			localName = String.join(" // ", faceNames);
		}
		return new CardSuggestions.LocalizedName(englishName, localName);
	}

	private static String preferTranslated(List<JsonNode> frenchFaces, String field, String englishValue) {
		String firstFrenchValue = null;
		for (JsonNode face : frenchFaces) {
			String value = text(face, field);
			if (value == null) {
				continue;
			}
			if (!value.equals(englishValue)) {
				return value;
			}
			if (firstFrenchValue == null) {
				firstFrenchValue = value;
			}
		}
		if (firstFrenchValue != null) {
			return firstFrenchValue;
		}
		return englishValue == null ? "" : englishValue;
	}

	private static boolean hasRealImage(JsonNode print) {
		return REAL_IMAGE_STATUSES.contains(print.path("image_status").asString("")) && !images(print).isEmpty();
	}

	/**
	 * Cartes double face : une image par face. Autres cartes, y compris les cartes partagées et les
	 * aventures qui ont plusieurs faces de texte : une seule image pour la carte entière.
	 */
	private static List<CardImage> images(JsonNode print) {
		String id = text(print, "id");
		List<CardImage> images = new ArrayList<>();
		String rootImage = imageUrl(print.get("image_uris"));
		if (rootImage != null) {
			images.add(new CardImage(id + "-0.jpg", rootImage));
			return images;
		}
		List<JsonNode> faces = faces(print);
		for (int index = 0; index < faces.size(); index++) {
			String faceImage = imageUrl(faces.get(index).get("image_uris"));
			if (faceImage != null) {
				images.add(new CardImage(id + "-" + index + ".jpg", faceImage));
			}
		}
		return images;
	}

	private static String imageUrl(JsonNode imageUris) {
		if (imageUris == null) {
			return null;
		}
		String large = text(imageUris, "large");
		return large != null ? large : text(imageUris, "normal");
	}

	private static List<JsonNode> faces(JsonNode card) {
		JsonNode faces = card.get("card_faces");
		List<JsonNode> result = new ArrayList<>();
		if (faces != null) {
			for (int index = 0; index < faces.size(); index++) {
				result.add(faces.get(index));
			}
		}
		return result;
	}

	/** La face demandée ; une carte sans {@code card_faces} est sa propre et unique face. */
	private static JsonNode face(JsonNode card, int index) {
		List<JsonNode> faces = faces(card);
		if (faces.isEmpty()) {
			return index == 0 ? card : null;
		}
		return index < faces.size() ? faces.get(index) : null;
	}

	/** Valeur texte du champ, ou null s'il est absent ou vide. */
	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return text.isBlank() ? null : text;
	}
}
