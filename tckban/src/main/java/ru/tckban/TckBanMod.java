package ru.tckban;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TckBanMod implements ModInitializer {

    private static final Map<UUID, TckSequence> ACTIVE = new HashMap<>();
    /** Игроки, чей настоящий кик нужно пропустить через миксин. */
    private static final Set<UUID> BYPASS = new HashSet<>();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, TckSequence>> it = ACTIVE.entrySet().iterator();
            while (it.hasNext()) {
                TckSequence seq = it.next().getValue();
                boolean done;
                try {
                    done = seq.tick();
                } catch (Exception e) {
                    e.printStackTrace();
                    seq.abort();
                    done = true;
                }
                if (done) {
                    it.remove();
                }
            }
        });

        // Чтобы бусик не остался в мире, если сервер выключили посреди сцены.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (TckSequence seq : ACTIVE.values()) {
                seq.abort();
            }
            ACTIVE.clear();
        });
    }

    /** @return true, если кик нужно отменить (вместо него едет ТЦК). */
    public static boolean intercept(ServerPlayerEntity player, Text reason) {
        UUID id = player.getUuid();
        if (BYPASS.remove(id)) {
            return false;
        }
        if (!isBanReason(reason)) {
            return false;
        }
        if (!ACTIVE.containsKey(id)) {
            ACTIVE.put(id, new TckSequence(player, reason));
        }
        return true;
    }

    /** Настоящий кик в конце сцены. */
    public static void release(ServerPlayerEntity player, Text reason) {
        UUID id = player.getUuid();
        BYPASS.add(id);
        try {
            player.networkHandler.disconnect(reason);
        } finally {
            BYPASS.remove(id);
        }
    }

    private static boolean isBanReason(Text reason) {
        if (reason.getContent() instanceof TranslatableTextContent t) {
            String key = t.getKey();
            return key.startsWith("multiplayer.disconnect.banned")
                    || key.startsWith("multiplayer.disconnect.ip_banned");
        }
        return false;
    }
}
