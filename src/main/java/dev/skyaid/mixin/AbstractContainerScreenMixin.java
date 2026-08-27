package dev.skyaid.mixin;

import dev.skyaid.feature.SkyMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences the tooltip of a slot SkyMenu has visually vacated: the item is
 * still there server-side, but it no longer appears to be - vanilla popping
 * its tooltip over the filler pane gave the relocation away. SkyMenu's own overlay supplies the tooltip at the item's new
 * home instead.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	protected Slot hoveredSlot;

	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void skyaid$muteVacatedSlots(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, CallbackInfo info) {
		if (hoveredSlot != null && SkyMenu.tooltipMuted(
				(AbstractContainerScreen<?>) (Object) this, hoveredSlot.index)) {
			info.cancel();
		}
	}
}
