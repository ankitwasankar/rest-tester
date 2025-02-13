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

    @GetMapping("/")
    public String index(Model model) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        model.addAttribute("files", files);
        return "index";
    }

    @PostMapping("/executeRequest")
    @ResponseBody
    public Mono<String> executeRequest(@RequestParam String fileName,
                                       @RequestParam String requestId,
                                       @RequestParam(defaultValue = "false") boolean skipSSL) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        for (YamlFileData fileData : files) {
            if (fileData.getFileName().equals(fileName)) {
                return fileData.getRequests().stream()
                        .filter(r -> r.getId().equals(requestId))
                        .findFirst()
                        .map(r -> requestService.executeRequest(r, fileData.getBaseUrl(), skipSSL))
                        .orElse(Mono.just("{\"error\": \"Request not found\"}"));
            }
        }
        return Mono.just("{\"error\": \"File not found\"}");
    }

    @PostMapping("/executeFile")
    @ResponseBody
    public Mono<List<String>> executeFile(@RequestParam String fileName,
                                          @RequestParam(defaultValue = "false") boolean skipSSL) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        for (YamlFileData fileData : files) {
            if (fileData.getFileName().equals(fileName)) {
                return requestService.executeFile(fileData, skipSSL);
            }
        }
        return Mono.just(List.of("{\"error\": \"File not found\"}"));
    }

    @PostMapping("/executeAll")
    @ResponseBody
    public Mono<List<String>> executeAll(@RequestParam(defaultValue = "false") boolean skipSSL) {
        List<YamlFileData> files = requestService.loadYamlFiles();
        return requestService.executeAll(files, skipSSL);
    }

    @PostMapping("/clearResponses")
    @ResponseBody
    public String clearResponses() {
        requestService.clearResponses();
        return "Cleared";
    }
}
