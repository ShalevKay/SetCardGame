package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * The dealer object.
     */
    private final Dealer dealer;

    /**
     * The table object.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private ThreadLogger aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The duration the player will be frozen for.
     */
    private long freezeTimeLeft;

    /**
     * True iff the player has been frozen after the key was pressed.
     */
    private boolean afterFreeze;

    /**
     * The queue of key presses.
     */
    private final BlockingQueue<Integer> keyPresses;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.score = 0;
        this.freezeTimeLeft = 0;
        this.afterFreeze = false;
        this.keyPresses = new ArrayBlockingQueue<>(Dealer.SET_SIZE);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            consumeKeyPress();
            verifySetWithDealer();
            freezeRemainingTime();
        }

        if (!human) try { aiThread.joinWithLog(); } catch (InterruptedException ignored) { }
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new ThreadLogger(() -> {

            while (!terminate) {
                freezeRemainingTime();
                generateKeyPress();
                verifySetWithDealer();
            }
        }, "computer-" + id, env.logger);
        aiThread.startWithLog();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // note: this is called by the UI thread, therefore waiting doesn't block the player's thread.
        synchronized (this) {
            while (keyPresses.remainingCapacity() == 0) {
                try { wait(); } catch (InterruptedException ignored) { }
            }
            keyPresses.add(slot);
            notifyAll();
        }
    }

    /**
     * Consume a key press from the queue of key presses, if available.
     * Then press the corresponding slot on the table.
     */
    public void consumeKeyPress() {
        // note: only this player's thread uses this method, therefore no need to synchronize
        try {
            if (!keyPresses.isEmpty()) {
                Integer slot = keyPresses.take();
                if (table.pressSlot(id, slot))
                    afterFreeze = false; // when a key is successfully pressed, the player is not frozen
            }
        } catch (InterruptedException ignored) { }
    }

    /**
     * Generate a key press for the AI (computer) player.
     */
    public void generateKeyPress() {
        // note: only the AI thread calls this method
        int slot = (int) (Math.random() * env.config.tableSize);
        if (table.pressSlot(id, slot))
            afterFreeze = false; // when a key is successfully pressed, the player is not frozen
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // note: only the dealer's thread calls this method, therefore no need to synchronize
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        freezeTimeLeft = env.config.pointFreezeMillis;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // note: only the dealer's thread calls this method, therefore no need to synchronize
        freezeTimeLeft = env.config.penaltyFreezeMillis;
    }

    /**
     * Get the current score of the player.
     */
    public int score() {
        // note: score is checked only after the game ends, therefore no need to synchronize
        return score;
    }

    /**
     * Freeze the remaining time of the player.
     */
    public void freezeRemainingTime() {
        // note: only this player's thread uses this method, therefore no need to synchronize
        try {
            if (freezeTimeLeft > 0 & !terminate) {
                afterFreeze = true;
                while (freezeTimeLeft > 0) {
                    env.ui.setFreeze(id, freezeTimeLeft);
                    long sleepTime = Math.min(freezeTimeLeft, Dealer.ONE_SECOND);
                    Thread.sleep(sleepTime);
                    freezeTimeLeft -= sleepTime;
                }
                freezeTimeLeft = 0;
                env.ui.setFreeze(id, 0);
            }
        }
        catch (InterruptedException ignored) {
            env.logger.info("Player thread was interrupted during freeze. This shouldn't happen!");
        }
    }

    public void verifySetWithDealer() {
        if (table.hasEnoughTokens(id) & !afterFreeze & !terminate) {
            dealer.addVerifyPlayer(this);
            if (dealer.isSleeping())
                dealer.getDealerThread().interrupt(); // wake up the dealer thread
            synchronized (this) {
                try {
                    wait();
                } // This is woken up by the dealer thread
                catch (InterruptedException ignored) {

                }
            }
        }
    }

    public Thread getPlayerThread() {
        return human ? playerThread : aiThread;
    }
}
