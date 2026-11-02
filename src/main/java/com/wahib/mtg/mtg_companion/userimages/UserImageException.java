package com.wahib.mtg.mtg_companion.userimages;

/** Image refusée ou impossible à enregistrer ; le message est destiné au joueur. */
public class UserImageException extends RuntimeException {

	private final int httpStatus;

	public UserImageException(int httpStatus, String message) {
		super(message);
		this.httpStatus = httpStatus;
	}

	public UserImageException(int httpStatus, String message, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
	}

	public int httpStatus() {
		return httpStatus;
	}
}
