package simpledb.buffer;

import org.junit.Before;
import org.junit.Test;
import simpledb.file.BlockId;
import simpledb.file.FileMgr;
import simpledb.log.LogMgr;

import java.io.File;

import static org.junit.Assert.*;

public class BufferMgrTest {
    private BufferMgr bufferMgr;
    private FileMgr fileMgr;
    private LogMgr logMgr;
    private static final int NUM_BUFFERS = 3;

    private BlockId block1, block2, block3, block4;

    @Before
    public void setUp() {
        // Initialize the FileMgr and LogMgr with a temporary directory
        File tempDir = new File("tempdb");
        tempDir.mkdir(); // Create the directory for testing
        fileMgr = new FileMgr(tempDir, 400); // Block size = 400 bytes
        logMgr = new LogMgr(fileMgr, "testlog");

        // Create a BufferMgr with 3 buffers
        bufferMgr = new BufferMgr(fileMgr, logMgr, NUM_BUFFERS);

        // Create test blocks
        block1 = new BlockId("testfile", 1);
        block2 = new BlockId("testfile", 2);
        block3 = new BlockId("testfile", 3);
        block4 = new BlockId("testfile", 4);
    }

    @Test
    public void testPinUnpin() {
        Buffer buffer = bufferMgr.pin(block1);
        assertNotNull(buffer); // Buffer should not be null
        assertTrue(buffer.isPinned()); // Buffer should be pinned

        bufferMgr.unpin(buffer);
        assertFalse(buffer.isPinned()); // Buffer should be unpinned
    }
   
    @Test
    public void testBufferAllocation() {
        for (int i = 0; i < NUM_BUFFERS; i++) {
            BlockId block = new BlockId("testfile", i);
            Buffer buffer = bufferMgr.pin(block);
            assertNotNull("Buffer should be allocated for each block", buffer);
        }

        try {
            BlockId block = new BlockId("testfile", NUM_BUFFERS);
            bufferMgr.pin(block);
            fail("BufferAbortException should be thrown when no buffers are available");
        } catch (BufferAbortException e) {
            // Expected exception
        }
    }
    
    @Test
    public void testBufferAvailability() {
        // Ensure all buffers are available initially
        int NUM_BUFFERS = 3; // Number of buffers in the buffer pool
        assertEquals("All buffers should be available initially", NUM_BUFFERS, bufferMgr.available());

        // Pin block1, reducing the number of available buffers
        Buffer buffer1 = bufferMgr.pin(block1);
        assertEquals("One buffer should be unavailable after pinning", NUM_BUFFERS - 1, bufferMgr.available());

        // Pin block2, further reducing availability
        Buffer buffer2 = bufferMgr.pin(block2);
        assertEquals("Two buffers should be unavailable after pinning another block", NUM_BUFFERS - 2, bufferMgr.available());

        // Pin block3, leaving no available buffers
        Buffer buffer3 = bufferMgr.pin(block3);
        assertEquals("No buffers should be available after pinning all blocks", 0, bufferMgr.available());

        bufferMgr.unpin(buffer1);
        assertEquals("One buffer should be available again after unpinning", 1, bufferMgr.available());

        // Unpin block2 and block3, making all buffers available

        bufferMgr.unpin(buffer2);

        bufferMgr.unpin(buffer3);
        assertEquals("All buffers should be available after unpinning all blocks", NUM_BUFFERS, bufferMgr.available());
    }


    @Test
    public void testDirtyBufferFlush() {
        // Pin block1 and mark it as dirty
        Buffer buffer = bufferMgr.pin(block1);
        buffer.setModified(100, 10); // Mark buffer as dirty
        bufferMgr.unpin(buffer);

        // Pin block2, which should cause a replacement if needed
        Buffer buffer2 = bufferMgr.pin(block2);
        // Ensure block1 was replaced by checking that block2 is now pinned
        assertTrue(buffer2.isPinned());
        assertEquals(block2, buffer2.block());

        // Confirm block1 was flushed by ensuring the buffer for block1 is now unpinned
        Buffer bufferForBlock1 = bufferMgr.pin(block1); // Re-pin block1
        assertNotNull(bufferForBlock1); // Block1 should still be accessible
        bufferMgr.unpin(bufferForBlock1); // Clean up
    }

    @Test
    public void testBufferLookup() {
        // Pin block1 and block2
        Buffer buffer1 = bufferMgr.pin(block1);
        Buffer buffer2 = bufferMgr.pin(block2);

     // Lookup block1 directly using bufferPoolMap (instead of findExistingBuffer)
        Buffer lookupBuffer = bufferMgr.bufferPoolMap.get(block1);
        assertSame(buffer1, lookupBuffer); // Should return the same buffer object

        // Unpin both buffers
        bufferMgr.unpin(buffer1);
        bufferMgr.unpin(buffer2);
    }

    @Test
    public void testLRUKReplacementPolicy() {
        // Pin 3 blocks (fills the buffer pool)
        Buffer buffer1 = bufferMgr.pin(block1);
        bufferMgr.unpin(buffer1);

        Buffer buffer2 = bufferMgr.pin(block2);
        bufferMgr.unpin(buffer2);

        Buffer buffer3 = bufferMgr.pin(block3);
        bufferMgr.unpin(buffer3);

        // Access block1 and block2 again to update their access history
        bufferMgr.pin(block1);
        bufferMgr.unpin(buffer1);

        bufferMgr.pin(block2);
        bufferMgr.unpin(buffer2);

        // Pin block4 (causing replacement of buffer with block3)
        Buffer buffer4 = bufferMgr.pin(block4);
        assertEquals(block4, buffer4.block());
        // Try to pin block3 again
        Buffer bufferForBlock3 = bufferMgr.pin(block3);

        // Ensure block3 is now assigned to a new or reassigned buffer
        assertNotSame(buffer4, bufferForBlock3); // Ensure block4's buffer is not reused for block3
        assertEquals(block3, bufferForBlock3.block()); // Ensure block3 is now in the buffer
    }

    @Test(expected = BufferAbortException.class)
    public void testBufferAbortOnExcessivePinRequests() {
        // Pin 3 blocks to fill the buffer pool
        bufferMgr.pin(block1);
        bufferMgr.pin(block2);
        bufferMgr.pin(block3);

        // Attempt to pin a fourth block without unpinning any (should timeout)
        bufferMgr.pin(block4);
    }
}
