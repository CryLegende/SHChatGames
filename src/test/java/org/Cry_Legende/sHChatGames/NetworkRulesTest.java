package org.Cry_Legende.sHChatGames;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRulesTest {

    @Test
    void firstAttemptClosesTheGameAndLaterOnesAreIgnored() {
        Referee referee = new Referee();
        referee.open("a1");
        assertTrue(referee.close("a1"));
        assertFalse(referee.close("a1"));
        assertFalse(referee.isOpen());
    }

    @Test
    void attemptForAnotherGameIsIgnored() {
        Referee referee = new Referee();
        referee.open("a2");
        assertFalse(referee.close("a1"));
        assertTrue(referee.isOpen());
        assertTrue(referee.close("a2"));
    }

    @Test
    void timeoutAndAttemptCannotBothClose() {
        Referee referee = new Referee();
        referee.open("a1");
        assertTrue(referee.close("a1"));   // une victoire est arrivée
        assertFalse(referee.close("a1"));  // le délai écoulé ne s'annonce pas en plus
    }

    @Test
    void abandonedGameHasNoWinner() {
        Referee referee = new Referee();
        referee.open("a1");
        referee.abandon();
        assertFalse(referee.close("a1"));
    }

    @Test
    void concurrentAttemptsGiveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 200; round++) {
            Referee referee = new Referee();
            referee.open("g");
            int servers = 8;
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(servers);
            for (int i = 0; i < servers; i++) {
                pool.execute(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (referee.close("g")) winners.incrementAndGet();
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, winners.get());
        }
    }

    @Test
    void newLeaderWaitsForTheEndOfTheLastGamePlusMargin() {
        RestartGuard guard = new RestartGuard();
        assertEquals(0L, guard.waitMs(1_000L, 5_000L));          // rien d'annoncé : libre
        guard.started("a1", 61_000L);                              // jeu prévu jusqu'à 61 s
        assertEquals(65_000L, guard.waitMs(1_000L, 5_000L));       // fin + marge = 66 s, il est 1 s
        assertEquals(1_000L, guard.waitMs(65_000L, 5_000L));
        assertEquals(0L, guard.waitMs(66_000L, 5_000L));
    }

    @Test
    void finishedGameStopsHoldingTheNextOne() {
        RestartGuard guard = new RestartGuard();
        guard.started("a1", 61_000L);
        guard.finished("a1", 4_000L);                              // gagné à 4 s
        assertEquals(5_000L, guard.waitMs(4_000L, 5_000L));        // seule la marge reste
        assertEquals(0L, guard.waitMs(9_000L, 5_000L));
    }

    @Test
    void endOfAnOlderGameDoesNotReleaseTheNewerOne() {
        RestartGuard guard = new RestartGuard();
        guard.started("a1", 61_000L);
        guard.started("a2", 200_000L);
        guard.finished("a1", 70_000L);                             // message tardif de l'ancien jeu
        assertEquals(135_000L, guard.waitMs(70_000L, 5_000L));
    }
}
