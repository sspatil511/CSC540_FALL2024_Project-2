package simpledb.test;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import simpledb.buffer.BufferMgrTest;

public class TestRunner {
    public static void main(String[] args) {
        // Run the JUnit test suite for BufferMgrTest
        Result result = JUnitCore.runClasses(BufferMgrTest.class);

        // Print results
        for (Failure failure : result.getFailures()) {
            System.out.println(failure.toString());
        }

        System.out.println("Test successful: " + result.wasSuccessful());
    }
}
