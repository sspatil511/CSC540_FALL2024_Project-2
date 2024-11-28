package simpledb.buffer;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import simpledb.file.*;
import simpledb.log.LogMgr;

/**
 * Manages the pinning and unpinning of buffers to blocks.
 * @author Edward Sciore
 *
 */
public class BufferMgr {
   private Buffer[] bufferpool;
   public Map<BlockId, Buffer> bufferPoolMap;
   private Map<Buffer, List<Long>> accessHistory;
   private int numAvailable;
   private static final long MAX_TIME = 10000; // 10 seconds
   private static final int K = 3;
   
   /**
    * Creates a buffer manager having the specified number 
    * of buffer slots.
    * This constructor depends on a {@link FileMgr} and
    * {@link simpledb.log.LogMgr LogMgr} object.
    * @param numbuffs the number of buffer slots to allocate
    */
   public BufferMgr(FileMgr fm, LogMgr lm, int numbuffs) {
      bufferpool = new Buffer[numbuffs];
      bufferPoolMap = new HashMap<>();
      accessHistory = new HashMap<>();
      numAvailable = numbuffs;
      for (int i = 0; i < numbuffs; i++) {
          Buffer buff = new Buffer(fm, lm);
          bufferpool[i] = buff;
          accessHistory.put(buff, new LinkedList<>()); // Initialize empty history
      }
   }
   
   /**
    * Returns the number of available (i.e. unpinned) buffers.
    * @return the number of available buffers
    */
   public synchronized int available() {
      return numAvailable;
   }
   
   /**
    * Flushes the dirty buffers modified by the specified transaction.
    * @param txnum the transaction's id number
    */
   public synchronized void flushAll(int txnum) {
      for (Buffer buff : bufferpool)
         if (buff.modifyingTx() == txnum)
         buff.flush();
   }
   
   
   /**
    * Unpins the specified data buffer. If its pin count
    * goes to zero, then notify any waiting threads.
    * @param buff the buffer to be unpinned
    */
   public synchronized void unpin(Buffer buff) {
      buff.unpin();
      if (!buff.isPinned()) {
         numAvailable++;
         recordAccess(buff);
         notifyAll();
      }
   }
   
   /**
    * Pins a buffer to the specified block, potentially
    * waiting until a buffer becomes available.
    * If no buffer becomes available within a fixed 
    * time period, then a {@link BufferAbortException} is thrown.
    * @param blk a reference to a disk block
    * @return the buffer pinned to that block
    */
   public synchronized Buffer pin(BlockId blk) {
      try {
         long timestamp = System.currentTimeMillis();
         Buffer buff = tryToPin(blk);
         while (buff == null && !waitingTooLong(timestamp)) {
            wait(MAX_TIME);
            buff = tryToPin(blk);
         }
         if (buff == null)
            throw new BufferAbortException();
         return buff;
      }
      catch(InterruptedException e) {
         throw new BufferAbortException();
      }
   }  
   
   private boolean waitingTooLong(long starttime) {
      return System.currentTimeMillis() - starttime > MAX_TIME;
   }
   
   /**
    * Tries to pin a buffer to the specified block. 
    * If there is already a buffer assigned to that block
    * then that buffer is used;  
    * otherwise, an unpinned buffer from the pool is chosen.
    * Returns a null value if there are no available buffers.
    * @param blk a reference to a disk block
    * @return the pinned buffer
    */
   
   private Buffer tryToPin(BlockId blk) {
       Buffer buff = bufferPoolMap.get(blk);
       if (buff == null) {
           buff = chooseUnpinnedBuffer();
           if (buff == null) return null;

           if (buff.block() != null) {
               bufferPoolMap.remove(buff.block());
           }
           buff.assignToBlock(blk);
           bufferPoolMap.put(blk, buff);
       }
       if (!buff.isPinned()) {
           numAvailable--;
       }
       recordAccess(buff); // Record pin access time
       buff.pin();
       return buff;
   }
   
   /*
   private Buffer findExistingBuffer(BlockId blk) {
      for (Buffer buff : bufferpool) {
         BlockId b = buff.block();
         if (b != null && b.equals(blk))
            return buff;
      }
      return null;
   }
   */
   
   private Buffer chooseUnpinnedBuffer() {
       Buffer candidate = null;
       long maxDistance = Long.MIN_VALUE;

       for (Buffer buff : bufferpool) {
           if (!buff.isPinned()) {
               long distance = calculateBackwardKDistance(buff);
               if (distance > maxDistance) {
                   maxDistance = distance;
                   candidate = buff;
               } else if (distance == maxDistance && candidate != null) {
                   // Fall back to traditional LRU if needed
                   long candidateLastAccess = getLastAccess(candidate);
                   long currentLastAccess = getLastAccess(buff);
                   if (currentLastAccess < candidateLastAccess) {
                       candidate = buff;
                   }
               }
           }
       }
       return candidate;
   }
   
   private long calculateBackwardKDistance(Buffer buff) {
       List<Long> history = accessHistory.get(buff);
       if (history.size() < K) {
           return Long.MAX_VALUE; // Backward K distance is ∞ if less than K accesses
       }
       return System.currentTimeMillis() - history.get(history.size() - K);
   }
   
   private long getLastAccess(Buffer buff) {
       List<Long> history = accessHistory.get(buff);
       return history.isEmpty() ? Long.MAX_VALUE : history.get(history.size() - 1);
   }
   
   private void recordAccess(Buffer buff) {
       List<Long> history = accessHistory.get(buff);
       history.add(System.currentTimeMillis());
       if (history.size() > K) {
           history.remove(0); // Keep only the last K accesses
       }
   }
}


