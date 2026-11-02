package com.wahib.mtg.mtg_companion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Fichier de sauvegarde isolé, pour ne jamais lire ni écraser la vraie partie
@SpringBootTest(properties = "mtg.state-file=target/test-state/context-loads.json")
class MtgCompanionApplicationTests {

	@Test
	void contextLoads() {
	}

}
