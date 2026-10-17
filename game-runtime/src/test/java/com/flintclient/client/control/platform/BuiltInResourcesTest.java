package com.flintclient.client.control.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** When Flint adds its own resource pack: only with no Fabric resource loader, which would make one already. */
class BuiltInResourcesTest {
	@Test
	void neededWithoutFabricApi() {
		assertTrue(BuiltInResources.needsPack(Set.of("flintclient", "fabricloader", "minecraft", "java")::contains));
	}

	@Test
	void notNeededWithEitherResourceLoader() {
		assertFalse(BuiltInResources.needsPack(Set.of("flintclient", "fabric-resource-loader-v1")::contains), "Fabric API 0.163, Sodium's bundled copy");
		assertFalse(BuiltInResources.needsPack(Set.of("flintclient", "fabric-resource-loader-v0")::contains), "the older module name");
		assertFalse(BuiltInResources.needsPack(Set.of("fabric-resource-loader-v0", "fabric-resource-loader-v1")::contains));
	}

	@Test
	void asksAboutBothLoaderIdsAndNothingElse() {
		List<String> asked = new ArrayList<>();
		BuiltInResources.needsPack(id -> {
			asked.add(id);
			return false;
		});
		assertEquals(List.of("fabric-resource-loader-v0", "fabric-resource-loader-v1"), asked);
	}

	@Test
	void otherFabricModulesDontCount() {
		assertTrue(BuiltInResources.needsPack(Set.of("fabric-api", "fabric-api-base", "fabric-key-mapping-api-v1")::contains),
			"only the resource loader turns mods into packs");
	}
}
