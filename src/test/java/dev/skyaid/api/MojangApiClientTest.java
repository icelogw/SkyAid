package dev.skyaid.api;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Mojang name resolution")
class MojangApiClientTest {
	@Test
	@DisplayName("the bare-hex UUID gets its dashes back")
	void parsesTheProfileResponse() {
		JsonObject body = new JsonObject();
		body.addProperty("id", "069a79f444e94726a5befca90e38aaf5");
		body.addProperty("name", "Notch");

		MojangApiClient.ResolvedPlayer resolved = MojangApiClient.parse(body).orElseThrow();

		assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
				resolved.uuid());
		assertEquals("Notch", resolved.name());
	}

	@Test
	void rejectsMalformedResponses() {
		assertTrue(MojangApiClient.parse(new JsonObject()).isEmpty());

		JsonObject shortId = new JsonObject();
		shortId.addProperty("id", "069a79f4");
		shortId.addProperty("name", "Notch");
		assertTrue(MojangApiClient.parse(shortId).isEmpty());

		JsonObject notHex = new JsonObject();
		notHex.addProperty("id", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
		notHex.addProperty("name", "Notch");
		assertTrue(MojangApiClient.parse(notHex).isEmpty());
	}

	@Test
	@DisplayName("only names safe to place in a URL are looked up")
	void validatesNames() {
		assertTrue(MojangApiClient.isValidName("Notch"));
		assertTrue(MojangApiClient.isValidName("ice_logw"));
		assertTrue(MojangApiClient.isValidName("Player486"));

		assertFalse(MojangApiClient.isValidName(""));
		assertFalse(MojangApiClient.isValidName("seventeen_letters"));
		assertFalse(MojangApiClient.isValidName("has space"));
		assertFalse(MojangApiClient.isValidName("slash/../etc"));
		assertFalse(MojangApiClient.isValidName("q?query=1"));
	}
}
