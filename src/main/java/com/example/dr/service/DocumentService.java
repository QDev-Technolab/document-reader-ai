package com.example.dr.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import ai.djl.translate.TranslateException;

@Service
public class DocumentService {
    
    private final EmbeddingService embeddingService;
    
    // Storage for processed document chunks and their embeddings
    private List<String> documentChunks = new ArrayList<>();
    private List<float[]> chunkEmbeddings = new ArrayList<>();
    private String fullDocumentText = ""; // Store full text for keyword search
    
    // Patterns for better context extraction
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(?i).*(\\d{1,2}\\s*[:.]\\s*\\d{2}\\s*(?:am|pm|AM|PM)?|\\d{1,2}\\s*(?:am|pm|AM|PM)|office\\s+hours?|working\\s+hours?|timing|schedule).*"
    );
    
    private static final Pattern POLICY_KEYWORDS = Pattern.compile(
        "(?i).*(policy|rule|procedure|guideline|regulation|standard|requirement).*"
    );
    
    public DocumentService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }
    
    public int processDocument(MultipartFile file, int chunkSize) throws Exception {
        // Clear previous document data
        documentChunks.clear();
        chunkEmbeddings.clear();
        
        // Extract text from file
        String documentText = extractTextFromFile(file);
        
        if (documentText == null || documentText.trim().isEmpty()) {
            throw new IllegalArgumentException("Unable to extract text from the uploaded file");
        }
        
        // Store full document text for keyword search
        fullDocumentText = documentText;
        
        // Split into chunks with better context preservation
        List<String> chunks = splitIntoChunksWithContext(documentText, chunkSize);
        
        // Generate embeddings for each chunk
        List<float[]> embeddings = embeddingService.embed(chunks);
        
        // Store chunks and embeddings
        documentChunks.addAll(chunks);
        chunkEmbeddings.addAll(embeddings);
        
        return chunks.size();
    }
    
    public List<String> findRelevantChunks(String question, int topK) throws TranslateException {
        if (documentChunks.isEmpty()) {
            throw new IllegalStateException("No document has been processed yet. Please upload a document first.");
        }
        
        // Use hybrid approach: semantic + keyword search
        List<String> semanticChunks = findSemanticChunks(question, topK);
        List<String> keywordChunks = findKeywordChunks(question, topK);
        
        // Combine and deduplicate results
        List<String> combinedChunks = new ArrayList<>(semanticChunks);
        
        // Add keyword chunks that aren't already included
        for (String keywordChunk : keywordChunks) {
            if (!combinedChunks.contains(keywordChunk)) {
                combinedChunks.add(keywordChunk);
            }
        }
        
        // If we still don't have enough chunks, add more semantic ones
        if (combinedChunks.size() < topK) {
            List<String> additionalChunks = findSemanticChunks(question, topK * 2);
            for (String chunk : additionalChunks) {
                if (!combinedChunks.contains(chunk) && combinedChunks.size() < topK) {
                    combinedChunks.add(chunk);
                }
            }
        }
        
        return combinedChunks.subList(0, Math.min(combinedChunks.size(), topK));
    }
    
    private List<String> findSemanticChunks(String question, int topK) throws TranslateException {
        // Generate embedding for the question
        float[] questionEmbedding = embeddingService.embed(question);
        
        // Calculate similarity scores
        List<ChunkScore> scores = IntStream.range(0, documentChunks.size())
            .mapToObj(i -> new ChunkScore(i, cosineSimilarity(questionEmbedding, chunkEmbeddings.get(i))))
            .sorted(Comparator.comparing(ChunkScore::getScore).reversed())
            .limit(topK)
            .toList();
        
        return scores.stream()
                    .map(score -> documentChunks.get(score.getIndex()))
                    .toList();
    }
    
    private List<String> findKeywordChunks(String question, int topK) {
        List<String> keywordChunks = new ArrayList<>();
        String lowerQuestion = question.toLowerCase();
        
        // Extract key terms from the question
        List<String> keywords = extractKeywords(lowerQuestion);
        
        // Find chunks that contain these keywords
        for (int i = 0; i < documentChunks.size(); i++) {
            String chunk = documentChunks.get(i).toLowerCase();
            int matchScore = 0;
            
            for (String keyword : keywords) {
                if (chunk.contains(keyword)) {
                    matchScore++;
                }
            }
            
            // Also check for special patterns based on question type
            if (isTimeRelatedQuestion(lowerQuestion) && TIME_PATTERN.matcher(chunk).find()) {
                matchScore += 3; // Higher weight for time-related content
            }
            
            if (matchScore > 0) {
                keywordChunks.add(documentChunks.get(i));
            }
        }
        
        return keywordChunks.stream().limit(topK).toList();
    }
    
    private List<String> extractKeywords(String question) {
        List<String> keywords = new ArrayList<>();
        
        // Remove common stop words and extract meaningful terms
        String[] words = question.replaceAll("[^a-zA-Z0-9\\s]", "").split("\\s+");
        
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() > 2 && !isStopWord(word)) {
                keywords.add(word);
                
                // Add related terms for better matching
                keywords.addAll(getRelatedTerms(word));
            }
        }
        
        return keywords;
    }
    
    private List<String> getRelatedTerms(String word) {
        // Map common question terms to document terms
        return switch (word) {
            case "timing", "time" -> List.of("hours", "schedule", "clock", "am", "pm", "start", "end");
            case "office" -> List.of("workplace", "work", "company", "organization");
            case "leave" -> List.of("vacation", "holiday", "absence", "time off", "pto");
            case "policy" -> List.of("rule", "procedure", "guideline", "regulation");
            case "salary" -> List.of("pay", "wage", "compensation", "remuneration");
            case "holiday" -> List.of("festival", "vacation", "leave", "off");
            default -> List.of();
        };
    }
    
    private boolean isTimeRelatedQuestion(String question) {
        return question.contains("timing") || question.contains("time") || 
               question.contains("hours") || question.contains("schedule") ||
               question.contains("when") || question.contains("what time");
    }
    
    private boolean isStopWord(String word) {
        List<String> stopWords = List.of("the", "is", "are", "was", "were", "and", "or", "but", 
                                        "in", "on", "at", "to", "for", "of", "with", "by", "what", "how");
        return stopWords.contains(word);
    }
    
    private List<String> splitIntoChunksWithContext(String text, int chunkSize) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> chunks = new ArrayList<>();
        
        // First, try to split by sections (headers, numbered points, etc.)
        String[] sections = text.split("(?=\\n\\s*\\d+\\.\\s|\\n\\s*[A-Z][A-Z\\s]+:|\\n\\s*\\*\\s|\\n\\s*-\\s)");
        
        StringBuilder currentChunk = new StringBuilder();
        
        for (String section : sections) {
            section = section.trim();
            if (section.isEmpty()) continue;
            
            // If this section is small enough to be a chunk by itself
            if (section.length() <= chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                chunks.add(section);
            } else if (currentChunk.length() + section.length() <= chunkSize * 1.2) {
                // Add to current chunk if it fits (with some flexibility)
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(section);
            } else {
                // Save current chunk and start processing this large section
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                
                // Split large section further
                List<String> subChunks = splitLargeSection(section, chunkSize);
                chunks.addAll(subChunks);
            }
        }
        
        // Don't forget the last chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks.isEmpty() ? List.of(text) : chunks;
    }
    
    private List<String> splitLargeSection(String section, int chunkSize) {
        // Split by paragraphs first
        String[] paragraphs = section.split("\\n\\s*\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;
            
            if (currentChunk.length() + paragraph.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder(paragraph);
            } else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks;
    }
    
    // Rest of the methods remain the same...
    private String extractTextFromFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("File name is null");
        }
        
        String extension = getFileExtension(filename).toLowerCase();
        
        return switch (extension) {
            case "pdf" -> extractFromPDF(file);
            case "docx" -> extractFromDOCX(file);
            case "txt" -> extractFromTXT(file);
            default -> throw new IllegalArgumentException("Unsupported file format: " + extension);
        };
    }
    
    private String extractFromPDF(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }
    
    private String extractFromDOCX(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append("\n");
            }
            return text.toString();
        }
    }
    
    private String extractFromTXT(MultipartFile file) throws IOException {
        return new String(file.getBytes());
    }
    
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return filename.substring(lastDotIndex + 1);
    }
    
    private float cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("Vectors must have the same length");
        }
        
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        
        float magnitude = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return magnitude == 0.0f ? 0.0f : dotProduct / magnitude;
    }
    
    // Helper class for sorting chunks by similarity score
    private static class ChunkScore {
        private final int index;
        private final float score;
        
        public ChunkScore(int index, float score) {
            this.index = index;
            this.score = score;
        }
        
        public int getIndex() { return index; }
        public float getScore() { return score; }
    }
    
    // Utility methods
    public int getDocumentChunkCount() {
        return documentChunks.size();
    }
    
    public boolean hasDocument() {
        return !documentChunks.isEmpty();
    }
    
    public List<String> getDocumentChunks() {
        return new ArrayList<>(documentChunks);
    }
    
    // Debug method to see what chunks contain specific keywords
    public List<String> debugSearchChunks(String keyword) {
        return documentChunks.stream()
                           .filter(chunk -> chunk.toLowerCase().contains(keyword.toLowerCase()))
                           .toList();
    }
}