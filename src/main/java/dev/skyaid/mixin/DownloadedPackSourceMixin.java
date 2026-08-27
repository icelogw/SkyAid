package dev.skyaid.mixin;

import dev.skyaid.feature.ResourcePackAccept;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.ServerPackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The one funnel every server pack decision passes through. Setting the
 * server entry to "Enabled" was not enough (log-verified 2026-08-25): when
 * Hypixel moves the client into Skyblock it RE-configures the connection,
 * and that fresh handler arrives here with PENDING - the prompt - no matter
 * what the server entry says. This swaps PENDING for ALLOWED on Hypixel
 * while the Auto resource pack toggle is on; every other server, and every
 * other status, passes through untouched.
 */
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
	@ModifyVariable(method = "configureForServerControl", at = @At("HEAD"),
			argsOnly = true)
	private ServerPackManager.PackPromptStatus skyaid$autoAllow(
			ServerPackManager.PackPromptStatus status,
			net.minecraft.network.Connection connection,
			ServerPackManager.PackPromptStatus original) {
		return ResourcePackAccept.promptOverride(status, connection);
	}
}
