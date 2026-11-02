package com.wahib.mtg.mtg_companion.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Détermine l'adresse locale du serveur à afficher sur la page de configuration.
 * On garde les IPv4 privées des interfaces actives, en écartant les cartes virtuelles
 * (Hyper-V, WSL, VirtualBox…) qui ne sont pas joignables depuis un téléphone.
 */
@Component
public class ServerAddressResolver {

	private static final Logger log = LoggerFactory.getLogger(ServerAddressResolver.class);
	private static final List<String> VIRTUAL_INTERFACE_KEYWORDS = List.of(
			"virtual", "vethernet", "hyper-v", "vmware", "vbox", "wsl", "docker", "loopback");
	/** L'énumération des interfaces est lente sous Windows : on garde le résultat un moment. */
	private static final Duration CACHE_DURATION = Duration.ofSeconds(30);

	private final Environment environment;
	private ServerAddresses cached;
	private Instant cachedAt = Instant.MIN;

	public ServerAddressResolver(Environment environment) {
		this.environment = environment;
	}

	public synchronized ServerAddresses resolve() {
		if (cached == null || Instant.now().isAfter(cachedAt.plus(CACHE_DURATION))) {
			cached = computeAddresses();
			cachedAt = Instant.now();
		}
		return cached;
	}

	private ServerAddresses computeAddresses() {
		// local.server.port est renseigné par Spring Boot une fois le serveur démarré (utile en port aléatoire)
		String port = environment.getProperty("local.server.port",
				environment.getProperty("server.port", "8080"));
		List<String> urls = findLanIpv4Addresses().stream()
				.map(ip -> "http://" + ip + ":" + port)
				.toList();
		if (urls.isEmpty()) {
			return new ServerAddresses("http://localhost:" + port, List.of());
		}
		return new ServerAddresses(urls.getFirst(), urls.subList(1, urls.size()));
	}

	private List<String> findLanIpv4Addresses() {
		List<String> addresses = new ArrayList<>();
		try {
			for (NetworkInterface networkInterface : NetworkInterface.networkInterfaces().toList()) {
				if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()
						|| looksVirtual(networkInterface)) {
					continue;
				}
				for (InetAddress address : networkInterface.inetAddresses().toList()) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						addresses.add(address.getHostAddress());
					}
				}
			}
		}
		catch (SocketException e) {
			log.warn("Impossible de lister les interfaces réseau", e);
		}
		addresses.sort(Comparator.comparingInt(ServerAddressResolver::preference));
		return addresses;
	}

	private static boolean looksVirtual(NetworkInterface networkInterface) {
		String description = (networkInterface.getName() + " " + networkInterface.getDisplayName())
				.toLowerCase(Locale.ROOT);
		return VIRTUAL_INTERFACE_KEYWORDS.stream().anyMatch(description::contains);
	}

	/** Les box domestiques utilisent presque toujours 192.168.x.x. */
	private static int preference(String ip) {
		if (ip.startsWith("192.168.")) {
			return 0;
		}
		if (ip.startsWith("10.")) {
			return 1;
		}
		return 2;
	}
}
