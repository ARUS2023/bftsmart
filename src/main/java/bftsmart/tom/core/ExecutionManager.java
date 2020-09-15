/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.tom.core;

import bftsmart.consensus.Consensus;
import bftsmart.consensus.Decision;
import bftsmart.consensus.Epoch;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.consensus.roles.Acceptor;
import bftsmart.consensus.roles.Proposer;
import bftsmart.reconfiguration.ServerViewController;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;


/**
 * This class manages consensus instances. It can have several epochs if
 * there were problems during consensus.
 *
 * @author Alysson
 */
public final class ExecutionManager {

    private ServerViewController controller;
    private Acceptor acceptor; // Acceptor role of the PaW algorithm
    private Proposer proposer; // Proposer role of the PaW algorithm
    //******* EDUARDO BEGIN: now these variables are all concentrated in the Reconfigurationmanager **************//
    //private int me; // This process ID
    //private int[] acceptors; // Process ID's of all replicas, including this one
    //private int[] otherAcceptors; // Process ID's of all replicas, except this one
    //******* EDUARDO END **************//
    private Map<Integer, Consensus> consensuses = new TreeMap<Integer, Consensus>(); // Consensuses
    private ReentrantLock consensusesLock = new ReentrantLock(); //lock for consensuses table
    // Paxos messages that were out of context (that didn't belong to the consensus that was/is is progress
    private Map<Integer, List<ConsensusMessage>> outOfContext = new HashMap<Integer, List<ConsensusMessage>>();
    // Proposes that were out of context (that belonged to future consensuses, and not the one running at the time)
    private Map<Integer, ConsensusMessage> outOfContextProposes = new HashMap<Integer, ConsensusMessage>();
    private ReentrantLock outOfContextLock = new ReentrantLock(); //lock for out of context
    private boolean stopped = false; // Is the execution manager stopped?
    // When the execution manager is stopped, incoming paxos messages are stored here
    private Queue<ConsensusMessage> stoppedMsgs = new LinkedList<ConsensusMessage>();
    private Epoch stoppedEpoch = null; // epoch at which the current consensus was stopped
    private ReentrantLock stoppedMsgsLock = new ReentrantLock(); //lock for stopped messages
    private TOMLayer tomLayer; // TOM layer associated with this execution manager
    private int paxosHighMark; // Paxos high mark for consensus instances
    
    /** THIS IS JOAO'S CODE, TO HANDLE THE STATE TRANSFER */
    
    private int revivalHighMark; // Paxos high mark for consensus instances when this replica CID equals 0
    private int timeoutHighMark; // Paxos high mark for a timed-out replica
    
    private int lastRemovedCID = 0; // Addition to fix memory leak

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ExecutionManager.class);
        
    /******************************************************************/
    
    // This is the new way of storing info about the leader,
    // uncoupled from any consensus instance
    private int currentLeader;
    
    /**
     * Creates a new instance of ExecutionManager
     *
     * @param controller
     * @param acceptor Acceptor role of the PaW algorithm
     * @param proposer Proposer role of the PaW algorithm
     * @param me This process ID
     */
    public ExecutionManager(ServerViewController controller, Acceptor acceptor,
                            Proposer proposer, int me) {
        //******* EDUARDO BEGIN **************//
        this.controller = controller;
        this.acceptor = acceptor;
        this.proposer = proposer;
        //this.me = me;

        this.paxosHighMark = this.controller.getStaticConf().getPaxosHighMark();
        /** THIS IS JOAO'S CODE, TO HANDLE THE STATE TRANSFER */
        this.revivalHighMark = this.controller.getStaticConf().getRevivalHighMark();
        this.timeoutHighMark = this.controller.getStaticConf().getTimeoutHighMark();
        /******************************************************************/
        //******* EDUARDO END **************//
        
        // Get initial leader
        if (controller.getCurrentViewAcceptors().length > 0)
            currentLeader = controller.getCurrentViewAcceptors()[0];
        else currentLeader = 0;
    }
    
    /**
     * Set the current leader
     * @param leader Current leader
     */
    public void setNewLeader (int leader) {
            this.currentLeader = leader;
    }

    /**
     * Get the current leader
     * @return Current leader
     */
    public int getCurrentLeader() {
            return currentLeader;
    }
        
    /**
     * Sets the TOM layer associated with this execution manager
     * @param tom The TOM layer associated with this execution manager
     */
    public void setTOMLayer(TOMLayer tom) {
        this.tomLayer = tom;

    }

    /**
     * Returns the TOM layer associated with this execution manager
     * @return The TOM layer associated with this execution manager
     */
    public TOMLayer getTOMLayer() {
        return tomLayer;
    }

    /**
     * Returns the acceptor role of the PaW algorithm
     * @return The acceptor role of the PaW algorithm
     */
    public Acceptor getAcceptor() {
        return acceptor;
    }

    public Proposer getProposer() {
        return proposer;
    }

    
    public boolean stopped() {
        return stopped;
    }

    public boolean hasMsgs() {
        return !stoppedMsgs.isEmpty();
    }

    public Queue<ConsensusMessage> getStoppedMsgs() {
        return stoppedMsgs;
    }
    
    public void clearStopped() {
        stoppedMsgs.clear();
    }
    /**
     * Stops this execution manager
     */
    public void stop() {
        LOGGER.debug("(ExecutionManager.stoping) Stoping execution manager");
        stoppedMsgsLock.lock();
        this.stopped = true;
        if (tomLayer.getInExec() != -1) {
            stoppedEpoch = getConsensus(tomLayer.getInExec()).getLastEpoch();
            //stoppedEpoch.getTimeoutTask().cancel();
//            if (stoppedEpoch != null) LOGGER.debug("(ExecutionManager.stop) Stoping epoch " + stoppedEpoch.getTimestamp() + " of consensus " + tomLayer.getInExec());

            if (stoppedEpoch != null) LOGGER.debug("(ExecutionManager.stop) I am proc {} Stoping epoch {} of consensus {}", controller.getStaticConf().getProcessId(), stoppedEpoch.getTimestamp(), tomLayer.getInExec());
//            if (stoppedEpoch != null) System.out.println("(ExecutionManager.stop) I am proc  " + controller.getStaticConf().getProcessId() + " Stoping epoch " + stoppedEpoch.getTimestamp() + " of consensus " + tomLayer.getInExec());
        }
        stoppedMsgsLock.unlock();
    }

    
    
    /**
     * Restarts this execution manager
     */
    public void restart() {
        LOGGER.debug("(ExecutionManager.restart) Starting execution manager");
        stoppedMsgsLock.lock();
        this.stopped = false;

        //process stopped messages
        while (!stoppedMsgs.isEmpty()) {
            ConsensusMessage pm = stoppedMsgs.remove();
            if (pm.getNumber() > tomLayer.getLastExec()) acceptor.processMessage(pm);
        }
        stoppedMsgsLock.unlock();
        LOGGER.debug("(ExecutionManager.restart) Finished stopped messages processing");
    }

    /**
     * Checks if this message can execute now. If it is not possible,
     * it is stored in outOfContextMessages
     *
     * @param msg the received message
     * @return true in case the message can be executed, false otherwise
     */
    public final boolean checkLimits(ConsensusMessage msg) {
        outOfContextLock.lock();
        
        int lastConsId = tomLayer.getLastExec();
        
        int inExec = tomLayer.getInExec();

        // If rollback occurs, this node no longer processes new messages, wait state transfer
        boolean rollHappend = tomLayer.execManager.getConsensus(lastConsId).getPrecomputeRolled();
        
        LOGGER.debug("(ExecutionManager.checkLimits) Received message {}", msg);
        LOGGER.debug("(ExecutionManager.checkLimits) I'm at consensus {} and my last consensus is {}",
                inExec, lastConsId);
        
        boolean isRetrievingState = tomLayer.isRetrievingState();

        if (isRetrievingState) {
            LOGGER.debug("(ExecutionManager.checkLimits) I'm waiting for a state");
        }

        boolean canProcessTheMessage = false;

        /** THIS IS JOAO'S CODE, TO HANDLE THE STATE TRANSFER */
        // This serves to re-direct the messages to the out of context
        // while a replica is receiving the state of the others and updating itself
        if (isRetrievingState || // Is this replica retrieving a state?
                (!(lastConsId == -1 && msg.getNumber() >= (lastConsId + revivalHighMark)) && //not a recovered replica
                (msg.getNumber() > lastConsId && (msg.getNumber() < (lastConsId + paxosHighMark))) && // not an ahead of time message
                !(stopped && msg.getNumber() >= (lastConsId + timeoutHighMark)))) { // not a timed-out replica which needs to fetch the state

            if (stopped) {//just an optimization to avoid calling the lock in normal case
                stoppedMsgsLock.lock();
                if (stopped) {
                    LOGGER.debug("(ExecutionManager.checkLimits) I am proc {} adding message for consensus {} to stopped, is retrive state : {}, last cid is {}, in exe cid is {}", controller.getStaticConf().getProcessId(), msg.getNumber(), isRetrievingState, lastConsId, inExec);
//                    System.out.println("(ExecutionManager.checkLimits) I am proc " + controller.getStaticConf().getProcessId() + " adding message for consensus " + msg.getNumber() + " to stoopped");
                    //the execution manager was stopped, the messages should be stored
                    //for later processing (when the consensus is restarted)
                    stoppedMsgs.add(msg);
                }
                stoppedMsgsLock.unlock();
            } else {
                if (isRetrievingState || 
                        msg.getNumber() > (lastConsId + 1) || 
                        (inExec != -1 && inExec < msg.getNumber()) || 
                        (inExec == -1 && msg.getType() != MessageFactory.PROPOSE)) { //not propose message for the next consensus

                    LOGGER.info("(ExecutionManager.checkLimits) I am proc {}, Message for consensus {} is out of context, adding it to out of context set, last cid is {}, in exe cid is {}", controller.getStaticConf().getProcessId(),
                            msg.getNumber(), lastConsId, inExec);


                    //System.out.println("(ExecutionManager.checkLimits) Message for consensus " + 
                     //       msg.getNumber() + " is out of context, adding it to out of context set; isRetrievingState="+isRetrievingState);
                    
                    
                    addOutOfContextMessage(msg);
                } else if (!rollHappend){ //can process!
                    LOGGER.info("(ExecutionManager.checkLimits) message for consensus {} can be processed", msg.getNumber());
            
                    //Logger.debug = false;
                    canProcessTheMessage = true;
                }
            }
        } else if ((lastConsId == -1 && msg.getNumber() >= (lastConsId + revivalHighMark)) || //recovered...
                (msg.getNumber() >= (lastConsId + paxosHighMark)) ||  //or too late replica...
                (stopped && msg.getNumber() >= (lastConsId + timeoutHighMark))) { // or a timed-out replica which needs to fetch the state

            LOGGER.info("(ExecutionManager.checkLimits) I am proc {}, start state transfer, last cid is {}, recv msg cid is {}, in cid is {}", controller.getStaticConf().getProcessId(), lastConsId, msg.getNumber(), inExec);
            LOGGER.info("I am proc {}, revivalHighMark is {}, paxosHighMark is {}, timeoutHighMark is {}", controller.getStaticConf().getProcessId(), revivalHighMark, paxosHighMark, timeoutHighMark);
            //Start state transfer
            /** THIS IS JOAO'S CODE, FOR HANLDING THE STATE TRANSFER */
            LOGGER.info("(ExecutionManager.checkLimits) Message for consensus {} is beyond the paxos highmark, adding it to out of context set", msg.getNumber());
            addOutOfContextMessage(msg);

            if (controller.getStaticConf().isStateTransferEnabled()) {
                //Logger.debug = true;
                tomLayer.getStateManager().analyzeState(msg.getNumber());
            }
            else {
                LOGGER.error("##################################################################################");
                LOGGER.error("- Ahead-of-time message discarded");
                LOGGER.error("- If many messages of the same consensus are discarded, the replica can halt!");
                LOGGER.error("- Try to increase the 'system.paxos.highMarc' configuration parameter.");
                LOGGER.error("- Last consensus executed: {}", lastConsId);
                LOGGER.error("##################################################################################");
            }
            /******************************************************************/
        }
        
        outOfContextLock.unlock();

        return canProcessTheMessage;
    }

    /**
     * Informs if there are messages till to be processed associated the specified consensus
     * @param cid The ID for the consensus in question
     * @return True if there are still messages to be processed, false otherwise
     */
    public boolean receivedOutOfContextPropose(int cid) {
        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        boolean result = outOfContextProposes.get(cid) != null;
        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();

        return result;
    }

    /**
     * Removes a consensus from this manager, use when rolling back
     * @param id ID of the consensus to be removed
     * @return void
     */
    public void removeSingleConsensus(int id) {

        consensusesLock.lock();
        consensuses.remove(id);
        consensusesLock.unlock();

        outOfContextLock.lock();

        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextProposes.remove(id);
        outOfContext.remove(id);

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();

    }

    /**
     * Removes a consensus from this manager
     * @param id ID of the consensus to be removed
     * @return The consensus that was removed
     */
    public Consensus removeConsensus(int id) {
        consensusesLock.lock();
        /******* BEGIN CONSENSUS CRITICAL SECTION *******/
        Consensus consensus = consensuses.remove(id);

        // Addition to fix memory leak
        for (int i = lastRemovedCID; i < id; i++) consensuses.remove(i);
        lastRemovedCID = id;
        
        /******* END CONSENSUS CRITICAL SECTION *******/
        consensusesLock.unlock();

        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextProposes.remove(id);
        outOfContext.remove(id);

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();

        return consensus;
    }

    /** THIS IS JOAO'S CODE, FOR HANDLING THE STATE TRANSFER */
    public void removeOutOfContexts(int id) {

        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        Integer[] keys = new Integer[outOfContextProposes.keySet().size()];
        outOfContextProposes.keySet().toArray(keys);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] <= id) {
                outOfContextProposes.remove(keys[i]);
            }
        }

        keys = new Integer[outOfContext.keySet().size()];
        outOfContext.keySet().toArray(keys);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] <= id) {
                outOfContext.remove(keys[i]);
            }
        }

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();
    }

    /********************************************************/
    /**
     * Returns the specified consensus
     *
     * @param cid ID of the consensus to be returned
     * @return The consensus specified
     */
    public Consensus getConsensus(int cid) {
        consensusesLock.lock();
        /******* BEGIN CONSENSUS CRITICAL SECTION *******/
        
        Consensus consensus = consensuses.get(cid);

        if (consensus == null) {//there is no consensus created with the given cid
            //let's create one...
            Decision dec = new Decision(cid);

            consensus = new Consensus(this, dec);

            //...and add it to the consensuses table
            consensuses.put(cid, consensus);
        }

        /******* END CONSENSUS CRITICAL SECTION *******/
        consensusesLock.unlock();

        return consensus;
    }

    /**
     * update consensus roll field
     *
     * @param cid ID of the consensus to be update
     * @return void
     */
    public void updateConsensus(int cid) {
        consensusesLock.lock();

       Consensus consensus =  consensuses.get(cid);

       consensus.setPrecomputeRolled();

       consensuses.remove(cid);

       consensuses.put(cid, consensus);

       consensusesLock.unlock();

    }

    public boolean isDecidable(int cid) {
        if (receivedOutOfContextPropose(cid)) {
            Consensus cons = getConsensus(cid);
            ConsensusMessage prop = outOfContextProposes.get(cons.getId());
            Epoch epoch = cons.getEpoch(prop.getEpoch(), controller);
            byte[] propHash = tomLayer.computeHash(prop.getValue());
            List<ConsensusMessage> msgs = outOfContext.get(cid);
            int countWrites = 0;
            int countAccepts = 0;
            if (msgs != null) {
                for (ConsensusMessage msg : msgs) {
                    // 对于Accept类型的共识消息，需要通过getOrigPropValue取到预计算之前的提议值hash
                    if (msg.getEpoch() == epoch.getTimestamp() &&
                            (Arrays.equals(propHash, msg.getValue()) || Arrays.equals(propHash, msg.getOrigPropValue()))) {
                        
                        if (msg.getType() == MessageFactory.WRITE) countWrites++;
                        else if (msg.getType() == MessageFactory.ACCEPT) countAccepts++;
                    }
                }
            }
            if(controller.getStaticConf().isBFT()){
            	return ((countWrites > (2*controller.getCurrentViewF())) &&
            			(countAccepts > (2*controller.getCurrentViewF())));
            }else{
            	return (countAccepts > controller.getQuorum());
            }
        }
        return false;
    }
    public void processOutOfContextPropose(Consensus consensus) {
        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        
        ConsensusMessage prop = outOfContextProposes.remove(consensus.getId());
        if (prop != null) {
            LOGGER.debug("(ExecutionManager.processOutOfContextPropose) {} Processing out of context propose", consensus.getId());
            acceptor.processMessage(prop);
        }

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();
    }

    public void processOutOfContext(Consensus consensus) {
        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        
        //then we have to put the pending paxos messages
        List<ConsensusMessage> messages = outOfContext.remove(consensus.getId());
        if (messages != null) {
            LOGGER.debug("(ExecutionManager.processOutOfContext) {} Processing other {} out of context messages.", consensus.getId(), messages.size());

            for (Iterator<ConsensusMessage> i = messages.iterator(); i.hasNext();) {
                acceptor.processMessage(i.next());
                if (consensus.isDecided()) {
                    LOGGER.debug("(ExecutionManager.processOutOfContext) consensus {} decided.", consensus.getId());
                    break;
                }
            }
            LOGGER.debug("(ExecutionManager.processOutOfContext) cid {} Finished out of context processing", consensus.getId());
        }

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();
    }

    /**
     * Stores a message established as being out of context (a message that
     * doesn't belong to current executing consensus).
     *
     * @param m Out of context message to be stored
     */
    public void addOutOfContextMessage(ConsensusMessage m) {
        outOfContextLock.lock();
        /******* BEGIN OUTOFCONTEXT CRITICAL SECTION *******/
        if (m.getType() == MessageFactory.PROPOSE) {
            LOGGER.debug("(ExecutionManager.addOutOfContextMessage) adding {}", m);
            outOfContextProposes.put(m.getNumber(), m);
        } else {
            List<ConsensusMessage> messages = outOfContext.get(m.getNumber());
            if (messages == null) {
                messages = new LinkedList<ConsensusMessage>();
                outOfContext.put(m.getNumber(), messages);
            }
            LOGGER.debug("(ExecutionManager.addOutOfContextMessage) adding {}", m);
            messages.add(m);

        }

        /******* END OUTOFCONTEXT CRITICAL SECTION *******/
        outOfContextLock.unlock();
    }

    @Override
    public String toString() {
        return stoppedMsgs.toString();
    }
}