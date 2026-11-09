package org.Cry_Legende.sHChatGames;

/**
 * Garde de reprise : tous les serveurs suivent la dernière partie annoncée, pour qu'un nouveau meneur
 * ne lance rien avant la fin de celle de son prédécesseur (fin prévue + une marge). Une partie terminée par
 * une victoire ou par sa fin annoncée libère tout de suite.
 */
final class RestartGuard {

    private String lastId = "";
    private long busyUntil;

    /** Une partie vient d'être annoncée, prévue pour finir à {@code endsAt} (millisecondes). */
    synchronized void started(String id, long endsAt) {
        lastId = id;
        busyUntil = endsAt;
    }

    /** La partie est terminée (victoire ou fin annoncée) : elle ne retient plus personne. */
    synchronized void finished(String id, long now) {
        if (id.equals(lastId) && now < busyUntil) {
            busyUntil = now;
        }
    }

    /** Millisecondes à attendre avant de pouvoir lancer une partie ; 0 si c'est possible. */
    synchronized long waitMs(long now, long marginMs) {
        if (lastId.isEmpty()) {
            return 0L; // aucune partie annoncée depuis le démarrage de ce serveur
        }
        return Math.max(0L, busyUntil + marginMs - now);
    }
}
