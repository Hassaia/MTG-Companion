package com.wahib.mtg.mtg_companion.userimages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Les fichiers de test ne contiennent que l'en-tête : c'est tout ce que lit {@link ImageFormat}. */
class ImageFormatTest {

	static byte[] png(int width, int height) {
		byte[] data = new byte[33];
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		System.arraycopy(signature, 0, data, 0, signature.length);
		data[11] = 13;
		System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, data, 12, 4);
		putBigEndian32(data, 16, width);
		putBigEndian32(data, 20, height);
		return data;
	}

	static byte[] jpeg(int width, int height) {
		byte[] data = new byte[40];
		data[0] = (byte) 0xFF;
		data[1] = (byte) 0xD8;
		// Segment APP0 de 16 octets, puis le début de trame (SOF0) qui porte les dimensions
		data[2] = (byte) 0xFF;
		data[3] = (byte) 0xE0;
		data[5] = 16;
		data[20] = (byte) 0xFF;
		data[21] = (byte) 0xC0;
		data[23] = 17;
		data[24] = 8;
		data[25] = (byte) (height >> 8);
		data[26] = (byte) height;
		data[27] = (byte) (width >> 8);
		data[28] = (byte) width;
		return data;
	}

	static byte[] webpExtended(int width, int height) {
		byte[] data = webpHeader("VP8X");
		putLittleEndian(data, 24, width - 1, 3);
		putLittleEndian(data, 27, height - 1, 3);
		return data;
	}

	static byte[] webpLossless(int width, int height) {
		byte[] data = webpHeader("VP8L");
		data[20] = 0x2F;
		putLittleEndian(data, 21, (width - 1) | ((height - 1) << 14), 4);
		return data;
	}

	static byte[] webpLossy(int width, int height) {
		byte[] data = webpHeader("VP8 ");
		data[23] = (byte) 0x9D;
		data[24] = 0x01;
		data[25] = 0x2A;
		putLittleEndian(data, 26, width, 2);
		putLittleEndian(data, 28, height, 2);
		return data;
	}

	private static byte[] webpHeader(String chunk) {
		byte[] data = new byte[40];
		System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
		System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, data, 8, 4);
		System.arraycopy(chunk.getBytes(StandardCharsets.US_ASCII), 0, data, 12, 4);
		return data;
	}

	private static void putBigEndian32(byte[] data, int offset, int value) {
		for (int index = 0; index < 4; index++) {
			data[offset + index] = (byte) (value >> (8 * (3 - index)));
		}
	}

	private static void putLittleEndian(byte[] data, int offset, int value, int length) {
		for (int index = 0; index < length; index++) {
			data[offset + index] = (byte) (value >> (8 * index));
		}
	}

	@Test
	void recognisesPng() {
		assertThat(ImageFormat.detect(png(1920, 1080))).hasValue(new ImageFormat.Detected("image/png", "png", 1920, 1080));
	}

	@Test
	void recognisesJpeg() {
		assertThat(ImageFormat.detect(jpeg(4000, 3000))).hasValue(new ImageFormat.Detected("image/jpeg", "jpg", 4000, 3000));
	}

	@Test
	void recognisesTheThreeWebpEncodings() {
		assertThat(ImageFormat.detect(webpExtended(2000, 1000)))
				.hasValue(new ImageFormat.Detected("image/webp", "webp", 2000, 1000));
		assertThat(ImageFormat.detect(webpLossless(640, 480)))
				.hasValue(new ImageFormat.Detected("image/webp", "webp", 640, 480));
		assertThat(ImageFormat.detect(webpLossy(800, 600)))
				.hasValue(new ImageFormat.Detected("image/webp", "webp", 800, 600));
	}

	@Test
	void rejectsSvgHtmlAndExecutableContent() {
		assertThat(ImageFormat.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8))).isEmpty();
		assertThat(ImageFormat.detect("<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
		assertThat(ImageFormat.detect(new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0})).isEmpty();
		// GIF : non pris en charge
		assertThat(ImageFormat.detect("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
	}

	@Test
	void rejectsEmptyAndTruncatedFiles() {
		assertThat(ImageFormat.detect(new byte[0])).isEmpty();
		assertThat(ImageFormat.detect(Arrays.copyOf(png(10, 10), 12))).isEmpty();
		assertThat(ImageFormat.detect(Arrays.copyOf(jpeg(10, 10), 6))).isEmpty();
		assertThat(ImageFormat.detect(Arrays.copyOf(webpExtended(10, 10), 20))).isEmpty();
	}

	@Test
	void rejectsDimensionsOutOfBounds() {
		assertThat(ImageFormat.detect(png(ImageFormat.MAX_DIMENSION + 1, 100))).isEmpty();
		assertThat(ImageFormat.detect(png(100, 0))).isEmpty();
		assertThat(ImageFormat.detect(jpeg(100, ImageFormat.MAX_DIMENSION + 1))).isEmpty();
		assertThat(ImageFormat.detect(png(ImageFormat.MAX_DIMENSION, ImageFormat.MAX_DIMENSION))).isPresent();
	}
}
