package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    private final ThreadLogger[] playerLoggers;
    private Thread dealerThread;
    private final Queue<Player> playerVerificationQueue;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    private boolean isSleeping;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public static final int ONE_SECOND = 1000;
    public static final int HUNDREDTH_SECOND = 10;
    public static final int SET_SIZE = 3;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.playerLoggers = new ThreadLogger[players.length];
        this.playerVerificationQueue = new ConcurrentLinkedQueue<>();
        this.deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.isSleeping = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {

        dealerThread = Thread.currentThread();

        for (Player player : players) {
            ThreadLogger ptl = new ThreadLogger(player, "player-" + player.id, env.logger);
            ptl.startWithLog();
            playerLoggers[player.id] = ptl;
        }

        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        closePlayerThreads();
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        updateTimerDisplay(true);
        while ((!terminate || !playerVerificationQueue.isEmpty()) && System.currentTimeMillis() < reshuffleTime) {
            verifyPlayersSets();
            removeCardsFromTable();
            placeCardsOnTable();
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            if (deck.isEmpty() && env.util.findSets(table.getCards(), 1).size() == 0) {
                terminate();
            }
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
            playerLoggers[i].interrupt();
        }
        terminate = true;
    }

    public void closePlayerThreads() {
        for (int i = players.length - 1; i >= 0; i--) {
            try { playerLoggers[i].joinWithLog(); } catch (InterruptedException ignored) { }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        table.removeShouldBeRemoved();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        boolean placedSomething = false;
        for (int slot = 0; slot < env.config.tableSize; slot++) {
            if (deck.isEmpty())
                break;
            if (table.slotToCard[slot] == null) {
                table.placeCard(deck.remove(0), slot);
                placedSomething = true;
            }
        }
        if (placedSomething && env.config.hints)
            table.hints();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        boolean isWarn = reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis;
        int sleepTime = isWarn ? HUNDREDTH_SECOND : ONE_SECOND;
        try { isSleeping = true; Thread.sleep(sleepTime); }
        catch (InterruptedException e) { env.logger.info("thread " + Thread.currentThread().getName() + " awakened."); }
        finally { isSleeping = false; }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        long timeLeft = reshuffleTime - System.currentTimeMillis();
        boolean isWarn = timeLeft < env.config.turnTimeoutWarningMillis;
        if (!isWarn) timeLeft = (long)Math.ceil((double)timeLeft / ONE_SECOND) * ONE_SECOND;
        env.ui.setCountdown(timeLeft, isWarn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (Integer card : table.slotToCard) {
            if (card != null) {
                deck.add(card);
                table.removeCard(table.cardToSlot[card]);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = -1;
        List<Integer> playerIdList = new LinkedList<>();
        for (Player player : players) {
            if (player.score() > maxScore) {
                playerIdList.clear();
                maxScore = player.score();
                playerIdList.add(player.id);
            } else if (player.score() == maxScore) {
                playerIdList.add(player.id);
            }
        }
        int[] playersIds = playerIdList.stream().mapToInt(i -> i).toArray();
        env.ui.announceWinner(playersIds);
    }

    public void verifyPlayersSets() {
        // note: only the dealer's thread calls this method, therefore no need to synchronize
        while (!playerVerificationQueue.isEmpty()) {
            Player player = playerVerificationQueue.poll();
            assert player != null;
            if (table.hasValidSet(player.id)) {
                int[] tokens = table.getTokens(player.id);
                for (int token : tokens) {
                    table.setShouldBeRemoved(token);
                }
                player.point();
                updateTimerDisplay(true);
            } else player.penalty();

            player.getPlayerThread().interrupt();
        }
    }

    public void addVerifyPlayer(Player player) {
        playerVerificationQueue.add(player);
    }

    public Thread getDealerThread() {
        return dealerThread;
    }

    public boolean isSleeping() {
        return isSleeping;
    }
}
