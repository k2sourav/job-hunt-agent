import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ClaudeAgentLoop {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String MODEL = "claude-sonnet-4-5";
    private static final String CONFIG_FILE_PATH = "/path/to/secret/config.properties";
    private static final String RESUME_FILE_PATH = "/path/to/resume.txt";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ClaudeAgentLoop() {
        this.apiKey = loadApiKey();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.mapper = new ObjectMapper();
    }

    private String loadApiKey() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE_PATH)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read config file at " + CONFIG_FILE_PATH, e);
        }
        String key = props.getProperty("anthropic.api.key");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("anthropic.api.key is empty in " + CONFIG_FILE_PATH);
        }
        return key;
    }

    /**
     * The tool itself: a plain Java method that reads your resume file from disk.
     * This is what actually runs when Claude asks to use the "read_resume" tool.
     */
    private String readResumeTool() {
        try {
            return Files.readString(Path.of(RESUME_FILE_PATH));
        } catch (IOException e) {
            return "Error reading resume file: " + e.getMessage();
        }
    }

    /**
     * Describes the tool to Claude, so it knows the tool exists and when to call it.
     */
    private ArrayNode buildToolDefinitions() {
        ArrayNode tools = mapper.createArrayNode();
        ObjectNode readResume = mapper.createObjectNode();
        readResume.put("name", "read_resume");
        readResume.put("description", "Reads the user's resume file and returns its full text content.");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        readResume.set("input_schema", schema);
        tools.add(readResume);
        return tools;
    }

    /**
     * The agent loop: keeps calling Claude, running any requested tool, and feeding
     * the result back, until Claude responds with plain text and no more tool calls.
     */
    public String runAgent(String userPrompt) throws Exception {
        ArrayNode conversation = mapper.createArrayNode();
        ObjectNode firstMessage = mapper.createObjectNode();
        firstMessage.put("role", "user");
        firstMessage.put("content", userPrompt);
        conversation.add(firstMessage);

        while (true) {
            JsonNode response = callClaude(conversation);
            JsonNode contentBlocks = response.path("content");

            boolean usedTool = false;
            StringBuilder finalText = new StringBuilder();

            // Claude's reply (whatever it contains) must be echoed back into the
            // conversation history before we add our tool result.
            ObjectNode assistantMessage = mapper.createObjectNode();
            assistantMessage.put("role", "assistant");
            assistantMessage.set("content", contentBlocks);
            conversation.add(assistantMessage);

            for (JsonNode block : contentBlocks) {
                String type = block.path("type").asText();

                if (type.equals("text")) {
                    finalText.append(block.path("text").asText());
                }

                if (type.equals("tool_use")) {
                    usedTool = true;
                    String toolName = block.path("name").asText();
                    String toolUseId = block.path("id").asText();

                    String toolResult;
                    if (toolName.equals("read_resume")) {
                        toolResult = readResumeTool();
                    } else {
                        toolResult = "Unknown tool: " + toolName;
                    }

                    // Feed the tool's result back in as a new user message,
                    // tagged with the matching tool_use_id.
                    ObjectNode toolResultMessage = mapper.createObjectNode();
                    toolResultMessage.put("role", "user");
                    ArrayNode toolResultContent = mapper.createArrayNode();
                    ObjectNode toolResultBlock = mapper.createObjectNode();
                    toolResultBlock.put("type", "tool_result");
                    toolResultBlock.put("tool_use_id", toolUseId);
                    toolResultBlock.put("content", toolResult);
                    toolResultContent.add(toolResultBlock);
                    toolResultMessage.set("content", toolResultContent);
                    conversation.add(toolResultMessage);
                }
            }

            // If Claude didn't ask for a tool this round, we're done -- return its answer.
            if (!usedTool) {
                return finalText.toString();
            }
            // Otherwise, loop again: Claude will see the tool result and respond further.
        }
    }

    private JsonNode callClaude(ArrayNode conversation) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 1024);
        root.set("messages", conversation);
        root.set("tools", buildToolDefinitions());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Claude API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    public static void main(String[] args) throws Exception {
        ClaudeAgentLoop agent = new ClaudeAgentLoop();
        String answer = agent.runAgent("What programming languages are on my resume?");
        System.out.println("Final answer: " + answer);
    }
}