package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HUD line ordering")
class HudLayoutTest {
	private static final String D = HudLayout.DIVIDER;

	@Test
	@DisplayName("the shipped default orders are already valid")
	void defaultOrderSurvivesSanitising() {
		// If sanitise changes them, a default names an unknown id or misses an
		// element - either way the shipped layout is not what anyone designed.
		assertEquals(HudLayout.defaultOrder(), HudLayout.sanitise(HudLayout.defaultOrder()));
		assertEquals(HudLayout.catacombsOrder(),
				HudLayout.sanitise(HudLayout.catacombsOrder()));
		assertEquals(HudLayout.gardenOrder(),
				HudLayout.sanitise(HudLayout.gardenOrder()));
	}

	@Test
	@DisplayName("a removed element stays removed instead of being re-appended")
	void removalMarkersStick() {
		List<String> withRemoval = List.of("location", "-purse", "bits");
		List<String> result = HudLayout.sanitise(withRemoval);

		assertTrue(result.contains("-purse"), "marker kept: " + result);
		assertTrue(!result.contains("purse"), "purse not re-appended: " + result);

		// Markers gather at the end, after every visible entry.
		assertTrue(result.indexOf("-purse") > result.indexOf("bits"));

		// And the renderer never draws one, even if content exists for the id.
		assertTrue(!HudLayout.normalise(result, Set.of("location", "purse", "bits"))
				.contains("-purse"));
	}

	@Test
	@DisplayName("a saved order keeps its sequence")
	void respectsSavedOrder() {
		List<String> saved = List.of("purse", "bits", "location");

		assertEquals(List.of("purse", "bits", "location"),
				HudLayout.sanitise(saved).subList(0, 3));
	}

	@Test
	@DisplayName("elements added in a later version go to the Add menu, not the layout")
	void newElementsArriveRemoved() {
		// The upgrade case, per the user's rule: a new readout must never insert
		// itself into a layout somebody already arranged - it waits in Add.
		List<String> old = List.of("location", "purse");
		List<String> result = HudLayout.sanitise(old);

		assertEquals("location", result.get(0));
		assertEquals("purse", result.get(1));

		// Present as a marker so it is offered back, never as a visible row.
		assertTrue(result.contains("-bits"));
		assertTrue(!result.contains("bits"));
	}

	@Test
	void dropsUnknownAndDuplicateIds() {
		List<String> messy = List.of("location", "removed_thing", "location", "purse");
		List<String> result = HudLayout.sanitise(messy);

		assertEquals(HudLayout.ELEMENTS.size(), result.size());
		assertEquals(1, result.stream().filter("location"::equals).count());
	}

	@Test
	void keepsEveryDividerThroughSanitising() {
		List<String> withDividers = List.of("location", D, "purse", D, "bits");

		assertEquals(2, HudLayout.sanitise(withDividers).stream().filter(D::equals).count());
	}

	@Test
	@DisplayName("elements with nothing to show are dropped")
	void dropsEmptyElements() {
		List<String> order = List.of("location", "purse", "bits");

		assertEquals(List.of("location", "bits"),
				HudLayout.normalise(order, Set.of("location", "bits")));
	}

	@Test
	@DisplayName("a divider only survives between two visible things")
	void dividersNeedSomethingEitherSide() {
		assertEquals(List.of("location", D, "purse"),
				HudLayout.normalise(List.of("location", D, "purse"),
						Set.of("location", "purse")));

		// Leading and trailing dividers have nothing to separate.
		assertEquals(List.of("location"),
				HudLayout.normalise(List.of(D, "location", D), Set.of("location")));

		// The element between two dividers vanished, so only one divider is left.
		assertEquals(List.of("location", D, "bits"),
				HudLayout.normalise(List.of("location", D, "purse", D, "bits"),
						Set.of("location", "bits")));
	}

	@Test
	void collapsesRunsOfDividers() {
		assertEquals(List.of("location", D, "purse"),
				HudLayout.normalise(List.of("location", D, D, D, "purse"),
						Set.of("location", "purse")));
	}

	@Test
	void nothingVisibleYieldsNothing() {
		assertTrue(HudLayout.normalise(List.of("location", D, "purse"), Set.of()).isEmpty());
	}
}
