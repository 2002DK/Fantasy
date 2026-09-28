package com.fantasy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:test",
		"app.players.sync-enabled=false"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
