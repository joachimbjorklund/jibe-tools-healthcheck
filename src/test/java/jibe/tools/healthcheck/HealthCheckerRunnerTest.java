package jibe.tools.healthcheck;

import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.testng.Assert.*;

public class HealthCheckerRunnerTest {

    private static final String HEALTHCHECK_ENDPOINT = "--healthcheck=http://localhost:8080/healthcheck,10,test-healthcheck-endpoint";
    private static int SERVER_PORT = 8080;

    @Test
    public void testRun() {
        assertFalse(healthcheck(HEALTHCHECK_ENDPOINT));

        Thread thread = new Thread(this::startSimpleHealthCheckedServer);
        thread.start();

        assertTrue(healthcheck(HEALTHCHECK_ENDPOINT));
    }

    private boolean healthcheck(String argument) {
        return new HealthCheckerRunner().run(singletonList(argument)).allOK();
    }

    private void startSimpleHealthCheckedServer()  {
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            try (Socket clientSocket = serverSocket.accept()) {
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
                out.write("HTTP/1.0 200 OK\r\n");
                out.write("\r\n");
                out.write("OK\n");
                out.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}