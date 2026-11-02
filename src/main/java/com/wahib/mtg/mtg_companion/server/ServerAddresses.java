package com.wahib.mtg.mtg_companion.server;

import java.util.List;

/**
 * @param primary adresse à ouvrir sur les téléphones, par exemple {@code http://192.168.1.20:8080}
 * @param others  autres adresses candidates, si le PC a plusieurs interfaces réseau
 */
public record ServerAddresses(String primary, List<String> others) {

	public ServerAddresses {
		others = List.copyOf(others);
	}
}
