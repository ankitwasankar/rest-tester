package org.test.apitester;


import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

public class FakeStoreApiSimulator {

    // Global cache for responses, stored as JsonNode objects keyed by request id.
    private static final Map<String, JsonNode> responses = new HashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    // Updated regex pattern to match references of the form {{op3.response.id}}
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{(\\w+\\.response(?:\\.\\w+)+)\\}\\}");

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter default base URL (e.g., https://fakestoreapi.com/): ");
        String defaultBaseUrl = scanner.nextLine().trim();
        if (!defaultBaseUrl.endsWith("/")) {
            defaultBaseUrl += "/";
        }
        System.out.println("\n########## Starting FakeStoreAPI Simulator ##########\n");

        // Load YAML files from resources/requests folder using the class loader.
        try {
            ClassLoader classLoader = FakeStoreApiSimulator.class.getClassLoader();
            URL resourceUrl = classLoader.getResource("requests");
            if (resourceUrl == null) {
                System.out.println("Could not locate the 'requests' folder in resources.");
                return;
            }
            File requestsFolder = new File(resourceUrl.toURI());
            File[] files = requestsFolder.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".yaml") || name.toLowerCase().endsWith(".yml")
            );
            if (files == null || files.length == 0) {
                System.out.println("No YAML files found in: " + requestsFolder.getAbsolutePath());
                return;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));

            for (File file : files) {
                printFileSeparator("Processing file: " + file.getName());
                processYamlFile(file, defaultBaseUrl);
                printFileSeparator("End of file: " + file.getName());
            }
        } catch (Exception e) {
            System.out.println("Error locating or processing YAML files: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("\n########## Simulator Finished ##########");
    }

    /**
     * Process a YAML file. A file can optionally define its own baseUrl.
     */
    private static void processYamlFile(File file, String defaultBaseUrl) {
        try (InputStream input = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Object data = yaml.load(input);
            String baseUrlForFile = defaultBaseUrl;
            List<Map<String, Object>> requestsList = new ArrayList<>();

            if (data instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> yamlMap = (Map<String, Object>) data;
                if (yamlMap.containsKey("baseUrl")) {
                    baseUrlForFile = yamlMap.get("baseUrl").toString();
                    if (!baseUrlForFile.endsWith("/")) {
                        baseUrlForFile += "/";
                    }
                }
                if (yamlMap.containsKey("requests") && yamlMap.get("requests") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> reqs = (List<Map<String, Object>>) yamlMap.get("requests");
                    requestsList.addAll(reqs);
                } else {
                    System.out.println("No 'requests' key found in file: " + file.getName());
                    return;
                }
            } else if (data instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reqs = (List<Map<String, Object>>) data;
                requestsList.addAll(reqs);
            } else {
                System.out.println("Unexpected YAML structure in file: " + file.getName());
                return;
            }

            for (Map<String, Object> requestMap : requestsList) {
                printRequestSeparator("Executing Request: " + requestMap.get("id"));
                processRequest(requestMap, baseUrlForFile);
                printRequestSeparator("End Request: " + requestMap.get("id"));
            }
        } catch (Exception e) {
            System.out.println("Error processing file " + file.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process a single API request.
     */
    private static void processRequest(Map<String, Object> requestMap, String baseUrl) {
        String id = (String) requestMap.get("id");
        String method = ((String) requestMap.get("method")).toUpperCase();
        String url = (String) requestMap.get("url");
        String requestBody = requestMap.containsKey("requestBody") ? requestMap.get("requestBody").toString() : null;

        // Parse headers if provided.
        Map<String, String> headers = new HashMap<>();
        if (requestMap.containsKey("headers")) {
            Object headersObj = requestMap.get("headers");
            if (headersObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> headerMap = (Map<Object, Object>) headersObj;
                headerMap.forEach((key, value) -> headers.put(key.toString(), value.toString()));
            }
        }

        // Resolve embedded references (e.g., {{op3.response.id}}) using JsonNode.
        url = resolveReferences(url);
        if (requestBody != null) {
            requestBody = resolveReferences(requestBody);
        }
        // Prepend base URL if URL is relative.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if(url.startsWith("/")) {
                url = url.substring(1);
            }
            url = baseUrl + url;
        }

        // Display request details.
        System.out.println("Method: " + method);
        System.out.println("URL: " + url);
        if (!headers.isEmpty()) {
            System.out.println("Headers: " + headers);
        }
        if (requestBody != null) {
            System.out.println("Request Body: " + requestBody);
        }

        // Execute the HTTP request.
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            headers.forEach(conn::setRequestProperty);
            conn.setDoInput(true);
            if (Arrays.asList("POST", "PUT", "PATCH").contains(method)) {
                conn.setDoOutput(true);
                if (requestBody != null) {
                    byte[] out = requestBody.getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Length", String.valueOf(out.length));
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(out);
                    }
                }
            }

            int responseCode = conn.getResponseCode();
            InputStream is = responseCode < HttpURLConnection.HTTP_BAD_REQUEST ? conn.getInputStream() : conn.getErrorStream();
            String responseText = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().reduce("", (acc, line) -> acc + line + "\n");

            System.out.println("Response Code: " + responseCode);
            System.out.println("Response: " + responseText);

            // Parse the response using ObjectMapper and store it.
            try {
                JsonNode jsonResponse = mapper.readTree(responseText);
                responses.put(id, jsonResponse);
            } catch (Exception ex) {
                System.out.println("Response is not valid JSON. Skipping JSON parsing for id: " + id);
            }
        } catch (Exception e) {
            System.out.println("Error executing request " + id + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Scans the given string for reference patterns (e.g., {{op3.response.id}})
     * and replaces them with the resolved values.
     */
    private static String resolveReferences(String input) {
        Matcher matcher = REF_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String ref = matcher.group(1); // Captured reference without curly braces.
            String resolvedValue = resolveReference(ref);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolvedValue));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a reference such as "op3.response.id" by navigating the stored JsonNode.
     */
    private static String resolveReference(String ref) {
        String[] parts = ref.split("\\.");
        if (parts.length < 3) return ref;
        String refId = parts[0];
        if (!responses.containsKey(refId)) {
            System.out.println("Warning: No stored response for reference id: " + refId);
            return ref;
        }
        JsonNode node = responses.get(refId);
        // Skip the literal "response" part (parts[1]) and navigate through subsequent parts.
        for (int i = 2; i < parts.length; i++) {
            if (node.has(parts[i])) {
                node = node.get(parts[i]);
            } else {
                System.out.println("Warning: Key '" + parts[i] + "' not found in response for id: " + refId);
                return ref;
            }
        }
        // Return the text value if it is a value node, else use its string representation.
        return node.isValueNode() ? node.asText() : node.toString();
    }

    // --- Simple Separators for Files and Requests ---

    private static void printFileSeparator(String text) {
        System.out.println("\n########## " + text + " ##########\n");
    }

    private static void printRequestSeparator(String text) {
        System.out.println("\n---- " + text + " ----\n");
    }
}
