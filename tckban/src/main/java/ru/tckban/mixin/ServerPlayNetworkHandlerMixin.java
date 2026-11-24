package ru.tckban.mixin;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.tckban.TckBanMod;

/**
 * В 1.21.4 disconnect(Text) объявлен в ServerCommonNetworkHandler.
 * /ban и /ban-ip кикают через него. Вместо мгновенного кика запускаем сцену с ТЦК,
 * а настоящий кик выполняем в конце сцены.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void tckban$onDisconnect(Text reason, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayNetworkHandler handler) {
            if (TckBanMod.intercept(handler.player, reason)) {
                ci.cancel();
            }
        }
    }
}
