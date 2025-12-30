package com.example.dr.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dr.service.DocumentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/debug")
@Tag(name = "Debug Controller", description = "APIs for debugging and testing document processing accuracy")
public class DebugController {

    private final DocumentService documentService;

    public DebugController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(
        summary = "Search for specific keywords in document chunks",
        description = "Find all chunks that contain a specific keyword - useful for debugging accuracy issues"
    )
    @GetMapping("/search-chunks")
    public ResponseEntity<Map<String, Object>> searchChunks(
            @RequestParam String keyword) {
        
        List<String> matchingChunks = documentService.debugSearchChunks(keyword);
        
        return ResponseEntity.ok(Map.of(
            "keyword", keyword,
            "totalMatches", matchingChunks.size(),
            "matchingChunks", matchingChunks,
            "totalChunks", documentService.getDocumentChunkCount()
        ));
    }

    @Operation(
        summary = "Get all document chunks",
        description = "Retrieve all processed document chunks for inspection"
    )
    @GetMapping("/all-chunks")
    public ResponseEntity<Map<String, Object>> getAllChunks() {
        
        List<String> allChunks = documentService.getDocumentChunks();
        
        return ResponseEntity.ok(Map.of(
            "totalChunks", allChunks.size(),
            "chunks", allChunks
        ));
    }

    @Operation(
        summary = "Find chunks containing time-related information",
        description = "Debug helper to find chunks that might contain timing information"
    )
    @GetMapping("/find-timing")
    public ResponseEntity<Map<String, Object>> findTimingChunks() {
        
        List<String> timeChunks = documentService.getDocumentChunks().stream()
            .filter(chunk -> {
                String lower = chunk.toLowerCase();
                return lower.contains("time") || lower.contains("hour") || 
                       lower.contains("am") || lower.contains("pm") ||
                       lower.contains("schedule") || lower.contains("timing") ||
                       lower.matches(".*\\d{1,2}\\s*[:.]\\s*\\d{2}.*") ||
                       lower.matches(".*\\d{1,2}\\s*(am|pm).*");
            })
            .toList();
        
        return ResponseEntity.ok(Map.of(
            "timingChunks", timeChunks,
            "totalTimingChunks", timeChunks.size(),
            "totalChunks", documentService.getDocumentChunkCount()
        ));
    }
}