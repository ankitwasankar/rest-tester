package com.example.rest_test.controller;

import com.example.rest_test.config.RequestConfig;
import com.example.rest_test.config.RequestProperties;
import com.example.rest_test.service.RequestExecutorService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
public class RequestController {

    private final RequestProperties requestProperties;
    private final RequestExecutorService executorService;

    public RequestController(RequestProperties requestProperties, RequestExecutorService executorService) {
        this.requestProperties = requestProperties;
        this.executorService = executorService;
    }

    // Display the list of request IDs with a button for each row.
    @GetMapping("/")
    public String showRequests(Model model) {
        List<RequestConfig> requests = requestProperties.getList();
        model.addAttribute("requests", requests);
        return "requests";
    }

    // When a button is clicked, execute all requests from start to that row.
    @PostMapping("/execute")
    public String executeRequests(@RequestParam("targetId") String targetId, Model model) {
        try {
            Map<String, JsonNode> responses = executorService.executeRequestsUpTo(targetId);
            model.addAttribute("responses", responses);
            model.addAttribute("message", "Executed requests up to " + targetId);
        } catch (Exception e) {
            model.addAttribute("message", "Error: " + e.getMessage());
        }
        // Redirect back to the view with execution results.
        List<RequestConfig> requests = requestProperties.getList();
        model.addAttribute("requests", requests);
        return "requests";
    }
}
