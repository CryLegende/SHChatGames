package org.Cry_Legende.sHChatGames.network;

import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * Ce dont le jeu en réseau a besoin du bus. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune
 * classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Joueurs connectés sur tout le réseau, ce serveur compris. */
    int online();

    /** Publie sur un canal ; ce serveur reçoit aussi son propre message. Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /**
     * Écoute un canal.
     *
     * @param mainThread vrai pour recevoir sur le fil principal ; faux pour recevoir sur le fil du bus, où le
     *                   gestionnaire ne doit ni attendre ni toucher au disque ou à la base
     */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** Exécute une commande console là où se trouve le joueur, ou à sa prochaine connexion s'il est hors ligne. */
    void runFor(String player, String command);

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);
}
