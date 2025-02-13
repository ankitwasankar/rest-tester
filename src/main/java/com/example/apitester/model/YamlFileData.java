package com.example.apitester.model;

import java.util.List;

public class YamlFileData {
    private String fileName;
    private String baseUrl;
    private List<RequestDefinition> requests;

    // Getters and setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<RequestDefinition> getRequests() { return requests; }
    public void setRequests(List<RequestDefinition> requests) { this.requests = requests; }
}
