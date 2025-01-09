package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * Mapping between a slot and if the token is placed in it (null if none)
     * for each player.
     */
    protected final Boolean[][] slotToToken; // token per slot (if any)

    /**
     * Mapping between a slot and if the card should be next removed.
     */
    protected final Boolean[] shouldBeRemoved; // token per slot (if any)

    /**
     * Locks for each slot.
     */
    private final Object[] slotLocks;

    /**
     * Locks for each player.
     */
    private final Object[] playerLocks;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.slotToToken = new Boolean[env.config.players][env.config.tableSize];
        this.shouldBeRemoved = new Boolean[env.config.tableSize];

        this.slotLocks = new Object[env.config.tableSize];
        for (int slot = 0; slot < env.config.tableSize; slot++)
            slotLocks[slot] = new Object();

        this.playerLocks = new Object[env.config.players];
        for (int player = 0; player < env.config.players; player++)
            playerLocks[player] = new Object();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        synchronized (this) {
            List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
            env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
                StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
                List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
                int[][] features = env.util.cardsToFeatures(set);
                System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
            });
        }
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        synchronized (this) {
            int cards = 0;
            for (Integer card : slotToCard)
                if (card != null)
                    ++cards;
            return cards;
        }
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        synchronized (slotLocks[slot]) {
            synchronized (playerLocks) {
                try { Thread.sleep(env.config.tableDelayMillis); }
                catch (InterruptedException ignored) { }

                cardToSlot[card] = slot;
                slotToCard[slot] = card;
                env.ui.placeCard(card, slot);
                for (int player = 0; player < env.config.players; player++)
                    slotToToken[player][slot] = false;
            }
        }
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        synchronized (slotLocks[slot]) {
            synchronized (playerLocks) {
                try { Thread.sleep(env.config.tableDelayMillis); }
                catch (InterruptedException ignored) { }

                int card = slotToCard[slot];
                slotToCard[slot] = null;
                cardToSlot[card] = null;
                shouldBeRemoved[slot] = false;
                env.ui.removeCard(slot);
                for (int player = 0; player < env.config.players; player++)
                    removeToken(player, slot);
            }
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        synchronized (slotLocks[slot]) {
            synchronized (playerLocks[player]) {
                if (slotToCard[slot] != null) {
                    slotToToken[player][slot] = true;
                    env.ui.placeToken(player, slot);
                }
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        synchronized (slotLocks[slot]) {
            synchronized (playerLocks[player]) {
                if (slotToToken[player][slot] != null && slotToToken[player][slot]) {
                    slotToToken[player][slot] = false;
                    env.ui.removeToken(player, slot);
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * This method is called when a player presses a slot.
     * If the slot is empty, the player places a token in it.
     * If the slot is occupied by the player's token, the player removes it.
     *
     * @param player - the player that pressed the slot.
     * @param slot   - the slot that was pressed.
     */
    public boolean pressSlot(int player, int slot) {
        synchronized (slotLocks[slot]) {
            synchronized (playerLocks[player]) {
                if (removeToken(player, slot))
                    env.logger.info("Player " + player + " removed token in slot " + slot);
                else {
                    if (!hasEnoughTokens(player)) {
                        placeToken(player, slot);
                        env.logger.info("Player " + player + " placed token in slot " + slot);
                    } else return false;
                }
                return true;
            }
        }
    }

    public boolean hasEnoughTokens(int player) {
        synchronized (playerLocks[player]) {
            int tokens = 0;
            for (Boolean token : slotToToken[player])
                if (token != null && token)
                    ++tokens;
            if (tokens > 3)
                System.out.println("Player " + player + " has " + tokens + " tokens.");
            return tokens >= Dealer.SET_SIZE; // In theory, only == would be needed, but to avoid stupid mistakes...
        }
    }

    public int[] getTokens(int player) {
        synchronized (playerLocks[player]) {
            int[] tokens = new int[Dealer.SET_SIZE];
            Arrays.fill(tokens, -1); // to avoid nulls if array is not full
            int i = 0;
            for (int slot = 0; slot < env.config.tableSize; slot++)
                if (slotToToken[player][slot])
                    tokens[i++] = slot;
            return tokens;
        }
    }

    public boolean hasValidSet(int player) {
        // note: no need to synchronize, because the dealer only removes cards after validating all players in queue
        synchronized (playerLocks[player]) {
            int[] tokens = getTokens(player);
            if (Arrays.stream(tokens).anyMatch(slot -> slot == -1))
                return false; // not enough tokens
            if (Arrays.stream(tokens).anyMatch(slot -> shouldBeRemoved[slot] != null && shouldBeRemoved[slot]))
                return false; // one of the cards was already set to be removed
            int[] cards = Arrays.stream(tokens).map(slot -> slotToCard[slot]).toArray();
            return env.util.testSet(cards);
        }
    }

    public void setShouldBeRemoved(int slot) {
        // note: only dealer calls this method, therefore no need to synchronize
        shouldBeRemoved[slot] = true;
    }

    public void removeShouldBeRemoved() {
        // note: only dealer calls this method, therefore no need to synchronize
        for (int slot = 0; slot < env.config.tableSize; slot++)
            if (shouldBeRemoved[slot] != null && shouldBeRemoved[slot])
                removeCard(slot);
    }

    public List<Integer> getCards() {
        synchronized (this) {
            List<Integer> cards = new ArrayList<>();
            for (Integer card : slotToCard) {
                if (card != null) {
                    cards.add(card);
                }
            }
            return cards;
        }
    }

}
