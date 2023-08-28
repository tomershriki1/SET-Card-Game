package bguspl.set.ex;

import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
//import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

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
     * Game entities.
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
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    /**
     * the dealer of the game
     */
    private Dealer dealer;

    /**
     * num of tokens that belongs to the player on the table
     */
    private int numOfTokens;

    /**
     * the queue of actions that the player executed
     */
    private final BlockingQueue<Integer> queue;

    /**
     * Argument which indicates if the player's set got checked, and if it was, if the player got a penalty or a point.
     * if the set wasn't checked yet: flag = 0.
     * if the player got a point: flag = 1.
     * if the player got a penalty: flag = -1.
     * if due to technical issues the dealer wasn't able to check the set: flag = 2.
     */
    protected  volatile Integer flag;
    /**
     * chosen set queue
     */
    public Queue<Integer> setQueue;
    /**
     * indicates if the set in the player's  queue was checked
     */
    public volatile boolean checked;
    /**
     * The ai player's lock
     */
    public Object AIPlayerLock;

    /**
     * the player's lock
     */
    public Object playerLock;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.AIPlayerLock = new Object();
        this.playerLock = new Object();
        this.flag = 2;
        this.queue = new ArrayBlockingQueue<Integer>(3);
        this.setQueue = new ArrayBlockingQueue<Integer>(3);
        this.checked = false;
        this.numOfTokens = 0;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();

        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
             if (flag == 1) {
                point();
            }
            if (flag == -1) {
                penalty();
            }

            if (!checked && flag == 5 && this.queue.remainingCapacity() == 0) {
                synchronized (this) {
                    synchronized (dealer.dealerLock){
                        dealer.getDealersQueue().add(this.id);
                        dealer.dealerLock.notifyAll();
                    }
                    while (flag == 5) {
                        try {
                            this.wait();
                        } catch (InterruptedException ignored) {}
                    }
                }
            } else {
                setFlag(0);
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");

            while (!terminate) {

                if (this.flag == 0) {
                    Random rnd = new Random();
                    keyPressed(rnd.nextInt(table.slotToCard.length));
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
         removeAllThePlayerTokens();
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
         if (this.flag == 0 && table.slotToCard[slot] != null) {
            if (!table.removeToken(this, slot)) {
              if (queue.size()<3) {
                  queue.add(slot);
                  table.placeToken(this.id, slot);
                  checked = false;
                  if (queue.size() == 3){
                      flag = 5;
                  }
              }
            } else {
                queue.remove(slot);
                }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
         env.ui.setScore(id, ++score);

        long endTime = System.currentTimeMillis() + env.config.pointFreezeMillis;
        while (System.currentTimeMillis() < endTime) {
            env.ui.setFreeze(id, endTime-System.currentTimeMillis());
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
        env.ui.setFreeze(id, 0);
        setFlag(0);

        // int ignored = table.countCards(); // this part is just for demonstration in
        // the unit tests

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
         long endTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;

        while (System.currentTimeMillis() < endTime) {
            env.ui.setFreeze(id, endTime-System.currentTimeMillis());
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
            env.ui.setFreeze(id, 0);
        setFlag(0);
            checked = true;


    }

    public int score() {
        return score;
    }

    public BlockingQueue<Integer> getQueue() {
        return this.queue;
    }

    public Thread getPlayerThread() {
        return this.playerThread;
    }

    public void setFlag(int num){
        synchronized (playerLock){
            this.flag = num;
            playerLock.notify();
        }

    }

    public int getFlag(){
        return this.flag;
    }

    public void removeAllThePlayerTokens(){
        for (int i=0; i<queue.size(); i++){
            table.removeToken(this,queue.poll());
            numOfTokens--;
        }

    }

    public  int getNumOfTokens (){
        return  this.numOfTokens;
    }

}