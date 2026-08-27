package dev.skyaid.mixin;

import dev.skyaid.feature.TooltipScroll;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skyblock lore regularly outgrows the screen, and vanilla's positioner just
 * pins an over-tall tooltip so most of it is cropped. This hands the final
 * position to {@link TooltipScroll}, which lets the mouse wheel walk through
 * anything taller than the screen; tooltips that fit come back untouched.
 */
@Mixin(DefaultTooltipPositioner.class)
public abstract class DefaultTooltipPositionerMixin {
	@Inject(method = "positionTooltip(IIIIII)Lorg/joml/Vector2ic;",
			at = @At("RETURN"), cancellable = true)
	private void skyaid$scrollTallTooltips(
			int screenWidth, int screenHeight, int mouseX, int mouseY,
			int width, int height, CallbackInfoReturnable<Vector2ic> info) {
		Vector2ic vanilla = info.getReturnValue();
		int y = TooltipScroll.reposition(screenHeight, vanilla.y(), height);

		if (y != vanilla.y()) {
			info.setReturnValue(new Vector2i(vanilla.x(), y));
		}
	}
}
