package com.wahib.mtg.mtg_companion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Fichier de sauvegarde isolé, pour ne jamais lire ni écraser la vraie partie
@SpringBootTest(properties = { "mtg.state-file=target/test-state/context-loads.json",
		"mtg.player-art-file=target/test-state/context-loads-art.json", "mtg.user-images-dir=target/test-state/context-loads-images" })
class MtgCompanionApplicationTests {

	@Test
	void contextLoads() {
	}

}
