package com.example.apitester.model;

import java.util.Map;

public class RequestDefinition {
    private String id;
    private String method;
    private String url;
    private Map<String, String> headers;
    private String requestBody;

    // Fields for storing execution results (populated after execution)
    private String status;
    private String responseBody;

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}
