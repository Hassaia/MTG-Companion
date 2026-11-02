package com.wahib.mtg.mtg_companion.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Écritures de fichiers qui ne laissent jamais un fichier à moitié écrit, même en cas d'arrêt brutal. */
public final class AtomicFiles {

	private AtomicFiles() {
	}

	/** Écrit dans un fichier temporaire du même dossier, puis le renomme à la place de la cible. */
	public static void write(Path target, byte[] content) throws IOException {
		Path directory = target.toAbsolutePath().getParent();
		Files.createDirectories(directory);
		Path temporaryFile = directory.resolve(target.getFileName() + ".tmp");
		Files.write(temporaryFile, content);
		try {
			Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e) {
			Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
