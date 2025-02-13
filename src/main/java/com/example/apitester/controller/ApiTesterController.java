package com.example.apitester.controller;

import com.example.apitester.model.YamlFileData;
import com.example.apitester.service.RequestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Controller
public class ApiTesterController {

    @Autowired
    private RequestService requestService;

    // Display the main UI page
    @GetMapping("/")
    public String index(Model model) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        model.addAttribute("files", files);
        return "index";
    }

    // Execute a single request via AJAX
    @PostMapping("/executeRequest")
    @ResponseBody
    public Mono<String> executeRequest(@RequestParam String fileName, @RequestParam String requestId) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        for (YamlFileData fileData : files) {
            if (fileData.getFileName().equals(fileName)) {
                return fileData.getRequests().stream()
                        .filter(r -> r.getId().equals(requestId))
                        .findFirst()
                        .map(r -> requestService.executeRequest(r, fileData.getBaseUrl()))
                        .orElse(Mono.just("Request not found"));
            }
        }
        return Mono.just("File not found");
    }

    // Execute all requests in a file
    @PostMapping("/executeFile")
    @ResponseBody
    public Mono<List<String>> executeFile(@RequestParam String fileName) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        for (YamlFileData fileData : files) {
            if (fileData.getFileName().equals(fileName)) {
                return requestService.executeFile(fileData);
            }
        }
        return Mono.just(List.of("File not found"));
    }

    // Execute all requests across all files
    @PostMapping("/executeAll")
    @ResponseBody
    public Mono<List<String>> executeAll() {
        List<YamlFileData> files = requestService.loadYamlFiles();
        return requestService.executeAll(files);
    }

    // Clear stored responses (and optionally trigger a client-side clear of local storage)
    @PostMapping("/clearResponses")
    @ResponseBody
    public String clearResponses() {
        requestService.clearResponses();
        return "Cleared";
    }
}
