package dev.skyaid.mixin;

import dev.skyaid.feature.SignSearchAssist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When SkyAid replaces Hypixel's search sign with its own screen, vanilla's
 * removed() must NOT run: it sends the sign's (still empty) lines to the
 * server the moment the screen is swapped, which Hypixel reads as an empty
 * search and answers by reopening the market menu over SkyAid's screen. The
 * replacement sends the real update itself once the user submits or cancels.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {
	@Inject(method = "removed", at = @At("HEAD"), cancellable = true)
	private void skyaid$suppressHijackedSend(CallbackInfo info) {
		if (SignSearchAssist.claimRemoval((AbstractSignEditScreen) (Object) this)) {
			// Vanilla's own cleanup from the tail of removed() still runs.
			Minecraft.getInstance().textInputManager().stopTextInput();
			info.cancel();
		}
	}
}
