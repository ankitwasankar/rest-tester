package com.example.rest_test.service;

import com.example.rest_test.config.RequestConfig;
import com.example.rest_test.config.RequestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RequestExecutorService {

    private final RequestProperties requestProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Map to store responses using request id as key
    private final Map<String, JsonNode> responseStore = new HashMap<>();

    // Pattern to match placeholders like ${ABC1.Response.user.status}
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    public RequestExecutorService(RequestProperties requestProperties) {
        this.requestProperties = requestProperties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, JsonNode> executeRequestsUpTo(String targetId) throws Exception {
        List<RequestConfig> requests = requestProperties.getList();
        boolean reachedTarget = false;
        for (RequestConfig req : requests) {
            // Execute until the target request (inclusive) is reached.
            executeSingleRequest(req);
            if (req.getId().equals(targetId)) {
                reachedTarget = true;
                break;
            }
        }
        if (!reachedTarget) {
            throw new Exception("Target ID not found in requests list");
        }
        return responseStore;
    }

    private void executeSingleRequest(RequestConfig req) throws Exception {
        // Substitute placeholders in URL, headers, and body
        String url = substitute(req.getUrl());
        HttpHeaders headers = new HttpHeaders();
        if (req.getHeaders() != null) {
            req.getHeaders().forEach((key, value) -> {
                headers.add(key, substitute(value));
            });
        }
        String body = req.getBody() != null ? substitute(req.getBody()) : null;

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        HttpMethod httpMethod = HttpMethod.valueOf(req.getMethod().toUpperCase());

        // Execute the REST call
        ResponseEntity<String> responseEntity = restTemplate.exchange(url, httpMethod, entity, String.class);
        String responseBody = responseEntity.getBody();

        // Parse and store the response JSON
        JsonNode responseJson = responseBody != null ? objectMapper.readTree(responseBody) : null;
        responseStore.put(req.getId(), responseJson);
    }

    /**
     * Substitute all placeholders in the input string by looking up the corresponding
     * value from the responseStore. Example placeholder: ${ABC1.Response.user.status}
     */
    private String substitute(String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String placeholderContent = matcher.group(1);
            // Expecting format: <id>.Response.<jsonPath>
            String[] parts = placeholderContent.split("\\.Response\\.", 2);
            if (parts.length != 2) {
                continue; // skip invalid placeholder
            }
            String refId = parts[0];
            String jsonPath = parts[1];  // e.g., user.status

            JsonNode refResponse = responseStore.get(refId);
            String replacement = "";
            if (refResponse != null) {
                // Traverse the JSON tree using the jsonPath (split by dot)
                String[] fields = jsonPath.split("\\.");
                JsonNode currentNode = refResponse;
                for (String field : fields) {
                    if (currentNode != null) {
                        currentNode = currentNode.get(field);
                    } else {
                        break;
                    }
                }
                if (currentNode != null && !currentNode.isMissingNode()) {
                    replacement = currentNode.asText();
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

