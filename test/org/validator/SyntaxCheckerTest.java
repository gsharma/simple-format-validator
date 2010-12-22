package org.validator;

import java.io.File;
import java.io.PipedReader;
import java.io.PipedWriter;
import static org.junit.Assert.*;

import org.junit.Test;
import org.validator.SyntaxChecker.Result;
import org.validator.SyntaxChecker.ResultType;
import org.validator.SyntaxChecker.State;
import org.validator.SyntaxChecker.StreamingMode;
import org.validator.SyntaxChecker.ValidationPolicy;

public class SyntaxCheckerTest {
    @Test
    public void testStringMode() throws Exception {
        SyntaxChecker validator = new SyntaxChecker(StreamingMode.STRING,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, "(({[]}))");
        assertEquals(State.INITIALIZING, validator.getState());
        Result result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.VALID);
        System.out.println(result);
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());

        validator = new SyntaxChecker(StreamingMode.STRING,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, "(({[]})");
        assertEquals(State.INITIALIZING, validator.getState());
        result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.INVALID);
        System.out.println(result);
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());

        validator = new SyntaxChecker(StreamingMode.STRING,
                new ValidationPolicy[] { ValidationPolicy.BRACE },
                "(ajka(g{gagagag[222244]}ggaggg))gggg");
        assertEquals(State.INITIALIZING, validator.getState());
        result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.VALID);
        System.out.println(result);
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());

        validator = new SyntaxChecker(StreamingMode.STRING,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, ")(({[]})");
        result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.INVALID);
        System.out.println(result);
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());

        validator = new SyntaxChecker(StreamingMode.STRING,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, "(({[]})))");
        assertEquals(State.INITIALIZING, validator.getState());
        result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.INVALID);
        System.out.println(result);
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());
    }

    @Test
    public void testFileMode() throws Exception {
        String filePath = new File(".").getCanonicalPath()
                + "/src/org/validator/SyntaxChecker.java";
        SyntaxChecker validator = new SyntaxChecker(StreamingMode.FILE,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, filePath);
        assertEquals(State.INITIALIZING, validator.getState());
        Result result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());
        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.VALID);
        System.out.println(result);

        // sleep to make a bad change to source file
        // Thread.sleep(10000L);
        // Result result = validator.validateNow();
        // assertTrue(result.getResultType() == ResultType.INVALID);
        // validator.stopNow();
    }

    @Test
    public void testPipeMode() throws Exception {
        PipedWriter pipedWriter = new PipedWriter();
        PipedReader pipedReader = new PipedReader(pipedWriter);
        pipedWriter.write("(ajka(g{gagagag[222244]}ggaggg))gggg");
        pipedWriter.flush();
        pipedWriter.close();
        SyntaxChecker validator = new SyntaxChecker(StreamingMode.PIPE,
                new ValidationPolicy[] { ValidationPolicy.BRACE }, pipedReader);
        assertEquals(State.INITIALIZING, validator.getState());
        Result result = validator.validateNow();
        assertEquals(State.PROCESSING, validator.getState());
        pipedReader.close();
        validator.stopNow();
        assertEquals(State.STOPPED, validator.getState());

        assertTrue(result.getDetailedResults()[0].getResultType() == ResultType.VALID);
        System.out.println(result);
    }
}
