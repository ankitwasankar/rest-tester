package com.example.apitester.service;

import com.example.apitester.model.RequestDefinition;
import com.example.apitester.model.YamlFileData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RequestService {

    private static final Logger logger = LoggerFactory.getLogger(RequestService.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    // Default WebClient (for normal SSL handling)
    private final WebClient defaultWebClient;

    // In-memory map to store responses keyed by request ID.
    private final Map<String, JsonNode> responses = new HashMap<>();

    // Pattern to find variables to be replaced: e.g., {{op3.response.parentProp.users[0].name}}
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    public RequestService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = new ObjectMapper();
        this.defaultWebClient = WebClient.create();
    }

    // Helper method to create a WebClient that skips SSL handshake if needed.
    private WebClient createWebClient(boolean skipSSL) {
        if (!skipSSL) {
            return defaultWebClient;
        }
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(sslContext));
            return WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        } catch (Exception ex) {
            logger.error("Failed to create insecure WebClient", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create insecure WebClient", ex);
        }
    }

    // Dynamically load all YAML files from classpath:requests folder.
    public List<YamlFileData> loadYamlFiles() {
        List<YamlFileData> filesData = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] yamlResources = resolver.getResources("classpath:requests/*.yaml");
            Resource[] ymlResources = resolver.getResources("classpath:requests/*.yml");
            List<Resource> allResources = new ArrayList<>();
            allResources.addAll(Arrays.asList(yamlResources));
            allResources.addAll(Arrays.asList(ymlResources));
            allResources.sort(Comparator.comparing(Resource::getFilename));
            for (Resource resource : allResources) {
                if (resource.exists()) {
                    YamlFileData fileData = loadYamlFile(resource);
                    filesData.add(fileData);
                }
            }
        } catch (Exception ex) {
            logger.error("Error loading YAML files", ex);
        }
        return filesData;
    }

    private YamlFileData loadYamlFile(Resource resource) throws Exception {
        YamlFileData fileData = new YamlFileData();
        fileData.setFileName(resource.getFilename());
        InputStream inputStream = resource.getInputStream();
        Yaml yaml = new Yaml();
        Object data = yaml.load(inputStream);
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;
            if (map.containsKey("baseUrl") && map.containsKey("requests")) {
                fileData.setBaseUrl(map.get("baseUrl").toString());
                List<Map<String, Object>> reqs = (List<Map<String, Object>>) map.get("requests");
                List<RequestDefinition> requests = new ArrayList<>();
                for (Map<String, Object> reqMap : reqs) {
                    RequestDefinition rd = parseRequestDefinition(reqMap);
                    requests.add(rd);
                }
                fileData.setRequests(requests);
            }
        } else if (data instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) data;
            List<RequestDefinition> requests = new ArrayList<>();
            for (Map<String, Object> reqMap : reqs) {
                RequestDefinition rd = parseRequestDefinition(reqMap);
                requests.add(rd);
            }
            fileData.setRequests(requests);
            fileData.setBaseUrl("");
        }
        return fileData;
    }

    private RequestDefinition parseRequestDefinition(Map<String, Object> reqMap) {
        RequestDefinition rd = new RequestDefinition();
        rd.setId(reqMap.get("id").toString());
        rd.setMethod(reqMap.get("method").toString());
        rd.setUrl(reqMap.get("url").toString());
        if (reqMap.containsKey("headers")) {
            rd.setHeaders((Map<String, String>) reqMap.get("headers"));
        }
        if (reqMap.containsKey("requestBody")) {
            rd.setRequestBody(reqMap.get("requestBody").toString());
        }
        return rd;
    }

    /**
     * Resolve references in the input string using regex.
     * For each variable of the form {{...}}, extract the reference,
     * use JsonNode traversal (supporting array indexing) to fetch its value from stored responses,
     * and replace it in the input string.
     * Throws an error if any reference cannot be resolved.
     */
    public String resolveReferences(String input) {
        if (input == null) return null;
        Matcher matcher = REF_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String ref = matcher.group(1).trim();  // e.g., "op3.response.parentProp.users[0].name"
            String replacement = resolveReferenceValue(ref);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolve a single reference (e.g., "op3.response.parentProp.users[0].name") by traversing stored responses.
     * Supports array indexing (e.g., users[0]). Throws an error if any field or index is not found.
     */
    private String resolveReferenceValue(String ref) {
        String[] parts = ref.split("\\.");
        if (parts.length < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reference format: " + ref);
        }
        String refId = parts[0];
        if (!responses.containsKey(refId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference replacement not found for: " + refId);
        }
        if (!"response".equalsIgnoreCase(parts[1])) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected 'response' as second part in reference: " + ref);
        }
        JsonNode node = responses.get(refId);
        for (int i = 2; i < parts.length; i++) {
            String part = parts[i];
            if (part.contains("[")) {
                int idxStart = part.indexOf('[');
                int idxEnd = part.indexOf(']');
                if (idxStart == -1 || idxEnd == -1 || idxEnd < idxStart) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid array notation in reference: " + part);
                }
                String field = part.substring(0, idxStart);
                String indexStr = part.substring(idxStart + 1, idxEnd);
                int index;
                try {
                    index = Integer.parseInt(indexStr);
                } catch (NumberFormatException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid array index in reference: " + part);
                }
                if (!node.has(field)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field '" + field + "' not found in response for: " + refId);
                }
                node = node.get(field);
                if (!node.isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field '" + field + "' is not an array in response for: " + refId);
                }
                if (index >= node.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Index " + index + " out of bounds for field '" + field + "' in response for: " + refId);
                }
                node = node.get(index);
            } else {
                if (!node.has(part)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference field '" + part + "' not found in response for: " + refId);
                }
                node = node.get(part);
            }
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /**
     * Execute a single request and store its response.
     * Returns a Mono<String> which is a JSON string like:
     * {"statusCode":200, "status": "Executed", "body": "<response body>"}.
     * The 'skipSSL' flag determines whether SSL validation is skipped.
     */
    public Mono<String> executeRequest(RequestDefinition rd, String baseUrl, boolean skipSSL) {
        String resolvedUrl;
        String resolvedBody;
        try {
            resolvedUrl = resolveReferences(rd.getUrl());
            resolvedBody = resolveReferences(rd.getRequestBody());
        } catch (ResponseStatusException ex) {
            logger.error("Reference replacement error for Request ID {}: {}", rd.getId(), ex.getReason());
            rd.setStatus("Error: " + ex.getReason());
            rd.setResponseBody("");
            return Mono.error(ex);
        }
        if (resolvedUrl.contains("{{") || resolvedUrl.contains("}}")) {
            String errMsg = "Reference replacement failed in URL: " + rd.getUrl();
            logger.error(errMsg);
            rd.setStatus("Error: " + errMsg);
            rd.setResponseBody("");
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, errMsg));
        }
        if (resolvedBody != null && (resolvedBody.contains("{{") || resolvedBody.contains("}}"))) {
            String errMsg = "Reference replacement failed in request body: " + rd.getRequestBody();
            logger.error(errMsg);
            rd.setStatus("Error: " + errMsg);
            rd.setResponseBody("");
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, errMsg));
        }
        if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
            if (resolvedUrl.startsWith("/")) {
                resolvedUrl = resolvedUrl.substring(1);
            }
            resolvedUrl = baseUrl + resolvedUrl;
        }
        logger.info("Executing Request: ID: {}, Method: {}, URL: {}, Headers: {}, Body: {}",
                rd.getId(), rd.getMethod(), resolvedUrl, rd.getHeaders(), resolvedBody);

        WebClient client = createWebClient(skipSSL);
        return client.method(org.springframework.http.HttpMethod.valueOf(rd.getMethod().toUpperCase()))
                .uri(resolvedUrl)
                .headers(httpHeaders -> {
                    if (rd.getHeaders() != null) {
                        rd.getHeaders().forEach(httpHeaders::add);
                    }
                })
                .bodyValue(resolvedBody != null ? resolvedBody : "")
                .exchangeToMono(clientResponse -> {
                    int statusCode = clientResponse.statusCode().value();
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                logger.info("Received Response for Request ID {}: status: {}, body: {}", rd.getId(), statusCode, body);
                                rd.setResponseBody(body);
                                String statusText;
                                if (statusCode < 400) {
                                    statusText = "Executed";
                                    try {
                                        JsonNode jsonNode = objectMapper.readTree(body);
                                        responses.put(rd.getId(), jsonNode);
                                    } catch (Exception ex) {
                                        logger.error("Failed to parse JSON response for Request ID " + rd.getId(), ex);
                                    }
                                } else {
                                    statusText = "Error: " + statusCode;
                                }
                                rd.setStatus(statusText);
                                Map<String, Object> resultMap = new HashMap<>();
                                resultMap.put("statusCode", statusCode);
                                resultMap.put("status", statusText);
                                resultMap.put("body", body);
                                try {
                                    String resultStr = objectMapper.writeValueAsString(resultMap);
                                    return Mono.just(resultStr);
                                } catch (Exception ex) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error processing response", ex));
                                }
                            });
                })
                .doOnError(ex -> {
                    String errorMsg = "Error executing request " + rd.getId() + ": " + ex.getMessage();
                    logger.error(errorMsg, ex);
                    rd.setStatus("Error: " + ex.getMessage());
                    rd.setResponseBody("");
                })
                .onErrorResume(ex -> {
                    Map<String, Object> errorMap = new HashMap<>();
                    int errorStatus = (ex instanceof ResponseStatusException) ?
                            ((ResponseStatusException) ex).getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value();
                    errorMap.put("statusCode", errorStatus);
                    errorMap.put("status", "Error");
                    errorMap.put("body", "{\"error\": {\"reason\": \"" + ex.getMessage() + "\"}}");
                    try {
                        String errorResult = objectMapper.writeValueAsString(errorMap);
                        return Mono.just(errorResult);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }

    // Execute all requests in a file (sequentially).
    public Mono<List<String>> executeFile(YamlFileData fileData, boolean skipSSL) {
        List<Mono<String>> monos = new ArrayList<>();
        for (RequestDefinition rd : fileData.getRequests()) {
            monos.add(executeRequest(rd, fileData.getBaseUrl(), skipSSL));
        }
        return Mono.zip(monos, results ->
                Arrays.stream(results)
                        .map(result -> (String) result)
                        .collect(Collectors.toList())
        );
    }

    // Execute all requests across all files (sequentially).
    public Mono<List<String>> executeAll(List<YamlFileData> filesData, boolean skipSSL) {
        List<Mono<String>> monos = new ArrayList<>();
        for (YamlFileData fileData : filesData) {
            for (RequestDefinition rd : fileData.getRequests()) {
                monos.add(executeRequest(rd, fileData.getBaseUrl(), skipSSL));
            }
        }
        return Mono.zip(monos, results ->
                Arrays.stream(results)
                        .map(result -> (String) result)
                        .collect(Collectors.toList())
        );
    }

    // Clear stored responses.
    public void clearResponses() {
        responses.clear();
    }
}
