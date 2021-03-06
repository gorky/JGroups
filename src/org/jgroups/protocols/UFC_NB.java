package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.Property;
import org.jgroups.util.Credit;
import org.jgroups.util.NonBlockingCredit;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Non-blocking alternative to {@link UFC}.<br/>
 * JIRA: https://issues.jboss.org/browse/JGRP-2172
 * @author Bela Ban
 * @since  4.0.4
 */
@MBean(description="Simple non-blocking flow control protocol based on a credit system")
public class UFC_NB extends UFC {
    @Property(description="Max number of bytes of all queued messages for a given destination. If a given destination " +
      "has no credits left and the message cannot be added to the queue because it is full, then the sender thread " +
      "will be blocked until there is again space available in the queue, or the protocol is stopped.")
    protected int                     max_queue_size=10_000_000;
    protected final Consumer<Message> send_function=msg -> down_prot.down(msg);


    public int      getMaxQueueSize()      {return max_queue_size;}
    public UFC_NB   setMaxQueueSize(int s) {this.max_queue_size=s; return this;}

    @ManagedAttribute(description="The number of messages currently queued due to insufficient credit")
    public int getNumberOfQueuedMessages() {
        return sent.values().stream().map(c -> ((NonBlockingCredit)c).getQueuedMessages()).reduce(0, (l,r) -> l+r);
    }

    @ManagedAttribute(description="The total size of all currently queued messages for all destinations")
    public int getQueuedSize() {
        return sent.values().stream().map(c -> ((NonBlockingCredit)c).getQueuedMessageSize()).reduce(0, (l,r) -> l+r);
    }

    @ManagedAttribute(description="The number of times messages have been queued due to insufficient credits")
    public int getNumberOfQueuings() {
        return sent.values().stream().map(c -> ((NonBlockingCredit)c).getEnqueuedMessages()).reduce(0, (l,r) -> l+r);
    }

    public boolean  isQueuingTo(Address dest) {
        NonBlockingCredit cred=(NonBlockingCredit)sent.get(dest);
        return cred != null && cred.isQueuing();
    }

    public int      getQueuedMessagesTo(Address dest) {
        NonBlockingCredit cred=(NonBlockingCredit)sent.get(dest);
        return cred != null? cred.getQueuedMessages() : 0;
    }

    @Override protected Object handleDownMessage(Message msg) {
        Address dest=msg.dest();
        if(dest == null) { // 2nd line of defense, not really needed
            log.error("%s doesn't handle multicast messages; passing message down", getClass().getSimpleName());
            return down_prot.down(msg);
        }

        Credit cred=sent.get(dest);
        if(cred == null)
            return down_prot.down(msg);

        int length=msg.length();
        if(running) {
            if(cred.decrementIfEnoughCredits(msg, length, 0)) // timeout is ignored
                return down_prot.down(msg);
            if(cred.needToSendCreditRequest(max_block_time))
                sendCreditRequest(dest, Math.max(0, max_credits - cred.get()));
            return null; // msg was queued
        }
        return down_prot.down(msg);
    }

    protected <T extends Credit> T createCredit(int initial_credits) {
        return (T)new NonBlockingCredit(initial_credits, max_queue_size, new ReentrantLock(true), send_function);
    }


}
