package com.example.apitester.service;

import com.example.apitester.model.RequestDefinition;
import com.example.apitester.model.YamlFileData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RequestService {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    // In-memory map to store responses keyed by request ID
    private final Map<String, JsonNode> responses = new HashMap<>();

    // Regex to match references like {{op3.response.id}}
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{(\\w+\\.response(?:\\.\\w+)+)\\}\\}");

    public RequestService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.create();
    }

    // Load YAML files from "classpath:requests" folder
    public List<YamlFileData> loadYamlFiles() {
        List<YamlFileData> filesData = new ArrayList<>();
        try {
            // Use ResourceLoader to get all YAML files from the requests folder.
            Resource[] resources = new Resource[] {
                    resourceLoader.getResource("classpath:requests/001-sample.yaml"),
                    resourceLoader.getResource("classpath:requests/002-sample.yaml")
            };
            // (In a real project, you might use a ResourcePatternResolver to load all files dynamically.)

            // Sort by filename
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

            for (Resource resource : resources) {
                if(resource.exists()){
                    YamlFileData fileData = loadYamlFile(resource);
                    filesData.add(fileData);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
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
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) data;
            List<RequestDefinition> requests = new ArrayList<>();
            for (Map<String, Object> reqMap : reqs) {
                RequestDefinition rd = parseRequestDefinition(reqMap);
                requests.add(rd);
            }
            fileData.setRequests(requests);
            // No baseUrl provided in this format; set as empty
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

    // Resolve references (e.g. {{op3.response.id}}) in a string
    public String resolveReferences(String input) {
        if(input == null) return null;
        Matcher matcher = REF_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String ref = matcher.group(1); // e.g. op3.response.id
            String resolved = resolveReference(ref);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // Resolve a reference using stored responses
    private String resolveReference(String ref) {
        String[] parts = ref.split("\\.");
        if (parts.length < 3) return ref;
        String refId = parts[0];
        if (!responses.containsKey(refId)) {
            return ref; // Alternatively, return empty string or error message
        }
        JsonNode node = responses.get(refId);
        for (int i = 2; i < parts.length; i++) {
            if (node.has(parts[i])) {
                node = node.get(parts[i]);
            } else {
                return ref;
            }
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    // Execute a single request and store its response
    public Mono<String> executeRequest(RequestDefinition rd, String baseUrl) {
        // Resolve references in URL and request body
        String resolvedUrl = resolveReferences(rd.getUrl());
        if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
            if (resolvedUrl.startsWith("/")) {
                resolvedUrl = resolvedUrl.substring(1);
            }
            resolvedUrl = baseUrl + resolvedUrl;
        }
        String resolvedBody = resolveReferences(rd.getRequestBody());

        WebClient.RequestBodySpec requestSpec = webClient.method(org.springframework.http.HttpMethod.valueOf(rd.getMethod().toUpperCase()))
                .uri(resolvedUrl);
        if (rd.getHeaders() != null) {
            for (Map.Entry<String, String> entry : rd.getHeaders().entrySet()) {
                requestSpec = requestSpec.header(entry.getKey(), entry.getValue());
            }
        }

        Mono<String> responseMono;
        if (Arrays.asList("POST", "PUT", "PATCH").contains(rd.getMethod().toUpperCase())) {
            responseMono = requestSpec.bodyValue(resolvedBody != null ? resolvedBody : "").retrieve()
                    .bodyToMono(String.class);
        } else {
            responseMono = requestSpec.retrieve().bodyToMono(String.class);
        }

        return responseMono.doOnNext(response -> {
            rd.setResponseBody(response);
            rd.setStatus("Executed");
            try {
                JsonNode jsonNode = objectMapper.readTree(response);
                responses.put(rd.getId(), jsonNode);
            } catch (Exception ex) {
                // If response is not valid JSON, skip storing
            }
        }).onErrorResume(ex -> {
            rd.setStatus("Error: " + ex.getMessage());
            rd.setResponseBody("");
            return Mono.just("Error: " + ex.getMessage());
        });
    }

    // Execute all requests in a file (sequentially)
    public Mono<List<String>> executeFile(YamlFileData fileData) {
        List<Mono<String>> monos = new ArrayList<>();
        for (RequestDefinition rd : fileData.getRequests()) {
            monos.add(executeRequest(rd, fileData.getBaseUrl()));
        }
        return Mono.zip(monos, results ->
                Arrays.stream(results)
                        .map(result -> (String) result)
                        .collect(Collectors.toList())
        );
    }

    // Execute all requests across all files (sequentially)
    public Mono<List<String>> executeAll(List<YamlFileData> filesData) {
        List<Mono<String>> monos = new ArrayList<>();
        for (YamlFileData fileData : filesData) {
            for (RequestDefinition rd : fileData.getRequests()) {
                monos.add(executeRequest(rd, fileData.getBaseUrl()));
            }
        }
        return Mono.zip(monos, results ->
                Arrays.stream(results)
                        .map(result -> (String) result)
                        .collect(Collectors.toList())
        );
    }

    // Clear stored responses
    public void clearResponses() {
        responses.clear();
    }
}
