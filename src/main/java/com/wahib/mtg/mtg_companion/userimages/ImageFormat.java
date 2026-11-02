package com.wahib.mtg.mtg_companion.userimages;

import java.util.Optional;

/**
 * Reconnaît JPEG, PNG et WebP d'après le contenu du fichier (jamais d'après son nom ou le type annoncé
 * par le navigateur) et lit leurs dimensions dans l'en-tête, sans décoder l'image : le fichier reste
 * tel quel. Tout le reste, SVG compris, est refusé.
 */
public final class ImageFormat {

	/** Au-delà, la mémoire du navigateur de la table serait mise à rude épreuve. */
	public static final int MAX_DIMENSION = 8000;

	/** @param extension extension du fichier stocké, sans point */
	public record Detected(String contentType, String extension, int width, int height) {
	}

	private ImageFormat() {
	}

	/** @return vide si le format n'est pas pris en charge, si le fichier est tronqué ou si les dimensions sont hors limites */
	public static Optional<Detected> detect(byte[] data) {
		Optional<Detected> detected;
		if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
			detected = jpeg(data);
		} else if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			detected = png(data);
		} else if (data.length >= 30 && ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")) {
			detected = webp(data);
		} else {
			detected = Optional.empty();
		}
		return detected.filter(image -> image.width() >= 1 && image.height() >= 1
				&& image.width() <= MAX_DIMENSION && image.height() <= MAX_DIMENSION);
	}

	private static Optional<Detected> jpeg(byte[] data) {
		int index = 2;
		while (index + 3 < data.length) {
			if (unsigned(data[index]) != 0xFF) {
				return Optional.empty();
			}
			int marker = unsigned(data[index + 1]);
			if (marker == 0xFF) {
				index++;
				continue;
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
				index += 2;
				continue;
			}
			if (marker == 0xD9 || marker == 0xDA) {
				// Fin de l'image ou début des données sans avoir rencontré les dimensions
				return Optional.empty();
			}
			boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
			if (startOfFrame) {
				if (index + 9 > data.length) {
					return Optional.empty();
				}
				int height = bigEndian16(data, index + 5);
				int width = bigEndian16(data, index + 7);
				return Optional.of(new Detected("image/jpeg", "jpg", width, height));
			}
			index += 2 + bigEndian16(data, index + 2);
		}
		return Optional.empty();
	}

	private static Optional<Detected> png(byte[] data) {
		if (data.length < 24 || !ascii(data, 12, "IHDR")) {
			return Optional.empty();
		}
		return Optional.of(new Detected("image/png", "png", bigEndian32(data, 16), bigEndian32(data, 20)));
	}

	private static Optional<Detected> webp(byte[] data) {
		int width;
		int height;
		if (ascii(data, 12, "VP8 ")) {
			if (unsigned(data[23]) != 0x9D || unsigned(data[24]) != 0x01 || unsigned(data[25]) != 0x2A) {
				return Optional.empty();
			}
			width = littleEndian(data, 26, 2) & 0x3FFF;
			height = littleEndian(data, 28, 2) & 0x3FFF;
		} else if (ascii(data, 12, "VP8L")) {
			if (unsigned(data[20]) != 0x2F) {
				return Optional.empty();
			}
			long bits = littleEndian(data, 21, 4);
			width = (int) (bits & 0x3FFF) + 1;
			height = (int) ((bits >> 14) & 0x3FFF) + 1;
		} else if (ascii(data, 12, "VP8X")) {
			width = littleEndian(data, 24, 3) + 1;
			height = littleEndian(data, 27, 3) + 1;
		} else {
			return Optional.empty();
		}
		return Optional.of(new Detected("image/webp", "webp", width, height));
	}

	private static boolean startsWith(byte[] data, int... signature) {
		if (data.length < signature.length) {
			return false;
		}
		for (int index = 0; index < signature.length; index++) {
			if (unsigned(data[index]) != signature[index]) {
				return false;
			}
		}
		return true;
	}

	private static boolean ascii(byte[] data, int offset, String text) {
		for (int index = 0; index < text.length(); index++) {
			if (data[offset + index] != text.charAt(index)) {
				return false;
			}
		}
		return true;
	}

	private static int unsigned(byte value) {
		return value & 0xFF;
	}

	private static int bigEndian16(byte[] data, int offset) {
		return (unsigned(data[offset]) << 8) | unsigned(data[offset + 1]);
	}

	private static int bigEndian32(byte[] data, int offset) {
		// Au-delà de 2^31 le résultat est négatif, donc refusé par les limites de dimensions
		return (unsigned(data[offset]) << 24) | (unsigned(data[offset + 1]) << 16)
				| (unsigned(data[offset + 2]) << 8) | unsigned(data[offset + 3]);
	}

	private static int littleEndian(byte[] data, int offset, int length) {
		int value = 0;
		for (int index = length - 1; index >= 0; index--) {
			value = (value << 8) | unsigned(data[offset + index]);
		}
		return value;
	}
}
