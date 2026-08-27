package dev.skyaid.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts a settings button next to SkyAid in Mod Menu's mod list.
 *
 * <p>Mod Menu is optional. This class is referenced only from the "modmenu"
 * entrypoint, which nothing reads unless Mod Menu is installed, so the class is
 * never loaded when it is absent - which is why Mod Menu can be a compileOnly
 * dependency without risking a NoClassDefFoundError at runtime.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
