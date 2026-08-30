import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Standalone local mock OpenAI-compatible server for manually validating the AI assessment UI
 * during runtime testing. NOT part of the plugin; NOT for production use; talks to no external
 * service and requires no API key. Run with: java MockAiServer.java [port]
 */
public class MockAiServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8765;
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/chat/completions", exchange -> {
            String content = "{\"mostLikelyCause\":\"The commit that bumped widget-core to 3.0\","
                    + "\"confidence\":\"HIGH\","
                    + "\"reasoning\":\"The only change recorded between the last successful build and "
                    + "this failure is the widget-core 3.0 bump, and the failure log shows a "
                    + "NoSuchMethodError immediately after that dependency initializes, which is "
                    + "consistent with a breaking API change in a major version bump.\","
                    + "\"supportingEvidence\":[\"commit 883390c: Bump widget-core to 3.0 (breaking "
                    + "change, not caught by review)\",\"log line: ERROR: dependency 'widget-core' "
                    + "failed to initialize (NoSuchMethodError)\"],"
                    + "\"recommendedChecks\":[\"Revert the widget-core version bump and re-run the "
                    + "build to confirm it passes again\",\"Check widget-core 3.0's release notes for "
                    + "removed or renamed methods\",\"If the upgrade is required, update the calling "
                    + "code to match the new widget-core API\"],"
                    + "\"insufficientEvidence\":false}";
            String body = "{\"choices\":[{\"message\":{\"content\":" + jsonEscape(content) + "}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            System.out.println("Served mock AI chat completion to " + exchange.getRemoteAddress());
        });
        server.start();
        System.out.println("Mock AI server listening on port " + port + " (path /chat/completions)");
    }

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
