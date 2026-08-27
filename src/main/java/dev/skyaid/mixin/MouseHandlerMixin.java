package dev.skyaid.mixin;

import dev.skyaid.feature.MouseLock;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mouse-lock tripod: while {@link MouseLock} is engaged, the private
 * turnPlayer step - the ONLY place accumulated mouse movement becomes player
 * rotation - is skipped. The surrounding handleAccumulatedMovement still
 * runs, so the deltas are consumed normally and releasing the lock does not
 * snap the camera. Nothing is ever injected; input is only ignored.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
	private void skyaid$freezeLook(double timeDelta, CallbackInfo info) {
		if (MouseLock.locked()) {
			info.cancel();
		}
	}
}
