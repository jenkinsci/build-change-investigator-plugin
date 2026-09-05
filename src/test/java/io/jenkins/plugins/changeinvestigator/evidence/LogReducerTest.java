package io.jenkins.plugins.changeinvestigator.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class LogReducerTest {

    @Test
    void capturesContextAroundErrorLines() throws IOException {
        // The marker lines must sit well outside the context window (2 lines before / 4 lines
        // after a match) around the single ERROR line, so this actually tests that distant,
        // unrelated lines are excluded rather than trivially including a short whole log.
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("FIRST LINE - must not appear in the excerpt");
        for (int i = 0; i < 15; i++) {
            lines.add("plain unrelated setup line " + i);
        }
        lines.add("ERROR: cannot find symbol Foo");
        lines.add("  at com.example.Main.main");
        for (int i = 0; i < 15; i++) {
            lines.add("plain unrelated teardown line " + i);
        }
        lines.add("LAST LINE - must not appear in the excerpt");
        String log = String.join("\n", lines);

        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(log)), 10_000);

        String joined = String.join("\n", result.lines);
        assertTrue(joined.contains("ERROR: cannot find symbol Foo"), joined);
        assertTrue(joined.contains("at com.example.Main.main"), joined);
        assertFalse(joined.contains("FIRST LINE"), joined);
        assertFalse(joined.contains("LAST LINE"), joined);
    }

    @Test
    void fallsBackToTailWhenNothingMatches() throws IOException {
        String log = "line one\nline two\nline three\nline four";
        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(log)), 10_000);

        String joined = String.join("\n", result.lines);
        assertTrue(joined.contains("line four"), joined);
        assertTrue(joined.toLowerCase().contains("no explicit error"), joined);
    }

    @Test
    void redactsSecretsInCapturedLines() throws IOException {
        String log = "ERROR: build failed\nAuthorization: Bearer sk-verysecrettoken1234\nCaused by: IOException";
        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(log)), 10_000);

        String joined = String.join("\n", result.lines);
        assertFalse(joined.contains("sk-verysecrettoken1234"), joined);
    }

    @Test
    void stripsAnsiEscapeCodesButKeepsBracketedText() throws IOException {
        // 0x1B is the real ANSI ESC control byte; ordinary "[INFO]"-style brackets in build
        // tool output (no ESC byte) must never be mistaken for an escape sequence.
        char esc = (char) 0x1B;
        String ansiRed = esc + "[31mERROR" + esc + "[0m: something failed";
        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(ansiRed)), 10_000);

        String joined = String.join("\n", result.lines);
        assertFalse(joined.indexOf(esc) >= 0, joined);
        assertFalse(joined.contains("[31m"), joined);
        assertTrue(joined.contains("ERROR"), joined);

        LogReducer.Result result2 =
                LogReducer.reduce(new BufferedReader(new StringReader("[INFO] ERROR count: 0")), 10_000);
        String joined2 = String.join("\n", result2.lines);
        assertTrue(joined2.contains("[INFO]"), joined2);
    }

    @Test
    void truncatesToMaxChars() throws IOException {
        String longLine = "ERROR " + "x".repeat(2000);
        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(longLine)), 100);

        String joined = String.join("\n", result.lines);
        assertTrue(joined.length() <= 100, "length=" + joined.length());
        assertTrue(result.truncated);
    }

    @Test
    void reportsLinesScanned() throws IOException {
        String log = String.join("\n", java.util.Collections.nCopies(25, "plain line"));
        LogReducer.Result result = LogReducer.reduce(new BufferedReader(new StringReader(log)), 10_000);
        assertTrue(result.linesScanned == 25, "scanned=" + result.linesScanned);
    }

    @Test
    void retainsQualifiedExceptionsAndDistantMavenExecutionContext() throws IOException {
        String log = "[INFO] --- exec:3.1:java (payments-integration-test) @ payment-common ---\n"
                + "ordinary output\n".repeat(30)
                + "java.lang.IllegalStateException: handshake refused\n"
                + " at com.acme.ServiceCheck.run(ServiceCheck.java:1)\n"
                + "ordinary output\n".repeat(30);
        String excerpt = String.join("\n", LogReducer.reduce(new BufferedReader(new StringReader(log)), 10000).lines);
        assertTrue(excerpt.contains("payments-integration-test"), excerpt);
        assertTrue(excerpt.contains("java.lang.IllegalStateException"), excerpt);
        assertTrue(excerpt.contains("ServiceCheck.java:1"), excerpt);
    }
}
