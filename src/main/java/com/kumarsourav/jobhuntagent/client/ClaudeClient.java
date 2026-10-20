import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String MODEL = "claude-sonnet-4-5";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ClaudeClient() {
        // Reads the key from an environment variable so it never ends up in source control.
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (this.apiKey == null || this.apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY environment variable is not set.");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Sends a single prompt to Claude and returns the plain text reply.
     */
    public String sendMessage(String prompt) throws Exception {
        String requestBody = buildRequestBody(prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Claude API error " + response.statusCode() + ": " + response.body());
        }

        return extractReplyText(response.body());
    }

    private String buildRequestBody(String prompt) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 1024);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        root.set("messages", messages);
        return mapper.writeValueAsString(root);
    }

    private String extractReplyText(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode content = root.path("content");
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        ClaudeClient client = new ClaudeClient();
        String reply = client.sendMessage("Say hello in one short sentence.");
        System.out.println("Claude says: " + reply);
    }
}