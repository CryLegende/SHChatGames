package org.Cry_Legende.sHChatGames.network;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHNetwork.api.LeaderHandle;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.util.function.Consumer;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public int online() {
        return api.online().size();
    }

    @Override
    public void publish(String channel, JsonObject message) {
        api.publish(channel, message.toString(), true);
    }

    @Override
    public void listen(String channel, Consumer<JsonObject> handler, boolean mainThread) {
        api.subscribe(channel, message -> {
            JsonObject json;
            try {
                json = message.object();
            } catch (RuntimeException e) {
                return; // message illisible : le bus ne s'arrête jamais pour lui
            }
            handler.accept(json);
        }, mainThread);
    }

    @Override
    public void runFor(String player, String command) {
        api.runFor(player, command);
    }

    @Override
    public Leader leader(String task, Runnable onAcquire, Runnable onLose) {
        LeaderHandle handle = api.leader(task, onAcquire, onLose);
        return new Leader() {
            @Override
            public boolean isLeader() {
                return handle.isLeader();
            }

            @Override
            public void release() {
                handle.release();
            }
        };
    }
}
