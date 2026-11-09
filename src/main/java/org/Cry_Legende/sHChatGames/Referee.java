package org.Cry_Legende.sHChatGames;

/**
 * Arbitre du meneur : une seule partie ouverte à la fois, et un seul appelant peut la fermer.
 * Le premier essai valable arrive ici avant la fin du délai, ou le délai passe avant : jamais les deux.
 */
final class Referee {

    private String open;

    /** Ouvre une partie ; une partie encore ouverte est abandonnée sans bruit. */
    synchronized void open(String id) {
        open = id;
    }

    /** Ferme la partie si elle est ouverte. Vrai pour le seul appelant qui la ferme. */
    synchronized boolean close(String id) {
        if (open == null || !open.equals(id)) {
            return false;
        }
        open = null;
        return true;
    }

    synchronized boolean isOpen() {
        return open != null;
    }

    /** Le serveur n'est plus meneur : la partie en cours n'a plus de gagnant possible ici. */
    synchronized void abandon() {
        open = null;
    }
}
