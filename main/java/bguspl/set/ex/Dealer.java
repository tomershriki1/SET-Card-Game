package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
//import java.util.Currency;
import java.util.Iterator;
//import java.util.LinkedList;
import java.util.List;
//import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
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

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * the dealer's queue that contains all the sets that are waiting to be checked
     */
    private final BlockingQueue<Integer> playerRequest;

    /**
     * the dealer's lock
     */

    public Object dealerLock;

    /**
     * The dealer's thread
     */
    private Thread dealerThread;

    private boolean activatePlayers;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playerRequest = new LinkedBlockingQueue<Integer>();
        dealerLock = new Object();
        activatePlayers = true;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        //dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++) {
            Thread player = new Thread(players[i]);
            player.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            long startTime = System.currentTimeMillis();
            updateTimerDisplay(false, startTime);
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        long startTime = System.currentTimeMillis();
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            if (!playerRequest.isEmpty()){
                checkSet(playerRequest.poll());
            }
            updateTimerDisplay(false, startTime);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
         for (Player player : players) {
            player.getPlayerThread().interrupt();
            player.terminate();
            try {
                player.getPlayerThread().join();
            } catch (InterruptedException ignored) {
            }
        }
        terminate = true;
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

    }


    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
            
        for (int i = 0; i < table.slotToCard.length && deck.size()>0; i++) {
            if (table.slotToCard[i] == null) {
                Random random = new Random();
                table.placeCard(deck.remove(random.nextInt(deck.size())), i);
            }
        }
        if (activatePlayers){
            for(int player=0; player<players.length; player++){
                players[player].setFlag(0);
            }
            activatePlayers = false;
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (dealerLock) {
            if(playerRequest.isEmpty()) {
                try {
                    dealerLock.wait(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset, long startTime) {
        long timeUntilReshuffle = startTime + env.config.turnTimeoutMillis - System.currentTimeMillis();
        if (timeUntilReshuffle <= env.config.turnTimeoutWarningMillis) {
            env.ui.setCountdown(timeUntilReshuffle, true);
            reset = false;
        } else {
            env.ui.setCountdown(timeUntilReshuffle, false);
            reset = false;
        }
        if (timeUntilReshuffle <= 0) {
            env.ui.setCountdown(0, true);
            reshuffle();
            timerLoop();
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
            
        //removing all the tokens from the table
        for (int player = 0; player < players.length; player++) {
            players[player].setFlag(2);
            players[player].removeAllThePlayerTokens();
        }
        env.ui.removeTokens();
        //removing all the cards from the table
        for (int slot = 0; slot < table.slotToCard.length; slot++) {
            if (table.slotToCard[slot] != null) {
                deck.add(table.slotToCard[slot]);
                table.removeCard(slot);
            }
        }
        if (env.util.findSets(deck, 1).isEmpty()){
            terminate = true;
        }

        //placing new cards on the table
        placeCardsOnTable();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    protected int[] announceWinners() {
                    int highestScore = 0;
        List<Integer> winnerList = new ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > highestScore) {
                highestScore = players[i].score();
                winnerList.clear();
                winnerList.add(players[i].id);
            } else if (players[i].score() == highestScore) {
                winnerList.add(players[i].id);
            }
        }
        int[] winnersArray = new int[winnerList.size()];
        Iterator<Integer> it = winnerList.iterator();
        int i = 0;
        while (it.hasNext()) {
            winnersArray[i] = it.next();
            i++;
        }
        env.ui.announceWinner(winnersArray);
        return winnersArray;
    }

    /** Reshuffle the cards on the table due to countdown Timeout */

    private void reshuffle() {
        removeAllCardsFromTable();
    }

    private void checkSet(int currentPlayer) {
        synchronized (players[currentPlayer]) {
            int[] setToCheck = new int[3];
            boolean slotIsNotNull = true;
            Iterator<Integer> it = players[currentPlayer].getQueue().iterator();
            int index = 0;
            while (it.hasNext() && slotIsNotNull) {
                Integer slot = it.next();
                if (table.slotToCard[slot] != null) {
                    setToCheck[index] = table.slotToCard[slot];
                    index = index + 1;
                } else {
                    table.removeToken(players[currentPlayer], slot);
                    players[currentPlayer].getQueue().remove(slot);
                    players[currentPlayer].checked = false;
                    slotIsNotNull = false;
                }
            }
            if (slotIsNotNull) {
                boolean legalSet = env.util.testSet(setToCheck);
                if (legalSet) {
                    table.removeSet(players[currentPlayer], deck);
                    players[currentPlayer].setFlag(1);
                } else {
                    players[currentPlayer].setFlag(-1);
                }
            }
            players[currentPlayer].notify();

        }
    }

    public BlockingQueue<Integer> getDealersQueue(){
        return this.playerRequest;
    }

    public List<Integer> getDeck(){
        return this.deck;
    }

}


