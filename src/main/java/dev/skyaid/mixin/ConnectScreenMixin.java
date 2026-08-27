package dev.skyaid.mixin;

import dev.skyaid.feature.ResourcePackAccept;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place the TYPED server address certainly exists, on the client
 * thread, before any connecting happens. The pack-decision hook runs on the
 * connector thread where the remote address has already collapsed to a bare
 * Cloudflare IP and the current-server field is not reliably visible
 * (log-verified 2026-08-25: "hypixel false" on a real Hypixel join) - so the
 * hostname is remembered here instead.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void skyaid$noteTarget(
			net.minecraft.client.gui.screens.Screen parent,
			net.minecraft.client.Minecraft minecraft,
			ServerAddress address,
			net.minecraft.client.multiplayer.ServerData serverData,
			boolean quickPlay,
			net.minecraft.client.multiplayer.TransferState transferState,
			CallbackInfo info) {
		ResourcePackAccept.noteTarget(address == null ? null : address.getHost());
	}
}
