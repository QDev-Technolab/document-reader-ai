package com.example.dr.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.dr.entity.Document;
import com.example.dr.entity.DocumentChunk;
import com.example.dr.repository.DocumentChunkRepository;
import com.example.dr.repository.DocumentRepository;

import ai.djl.translate.TranslateException;

@Service
public class DocumentService {

    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    // Patterns for better context extraction
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(?i).*(\\d{1,2}\\s*[:.]\\s*\\d{2}\\s*(?:am|pm|AM|PM)?|\\d{1,2}\\s*(?:am|pm|AM|PM)|office\\s+hours?|working\\s+hours?|timing|schedule).*"
    );

    private static final Pattern POLICY_KEYWORDS = Pattern.compile(
        "(?i).*(policy|rule|procedure|guideline|regulation|standard|requirement).*"
    );

    public DocumentService(
            EmbeddingService embeddingService,
            DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository) {
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }
    
    @Transactional
    public Long processDocument(MultipartFile file, int chunkSize) throws Exception {
        // Extract text from file
        String documentText = extractTextFromFile(file);

        if (documentText == null || documentText.trim().isEmpty()) {
            throw new IllegalArgumentException("Unable to extract text from the uploaded file");
        }

        // Create document entity
        Document document = Document.builder()
            .filename(file.getOriginalFilename())
            .fileExtension(getFileExtension(file.getOriginalFilename()))
            .fileSizeBytes(file.getSize())
            .chunkSize(chunkSize)
            .totalChunks(0)
            .fullText(documentText)
            .embeddingModel(embeddingService.getCurrentModel())
            .status(Document.DocumentStatus.PROCESSING)
            .build();

        // Save document first to get ID
        document = documentRepository.save(document);

        try {
            // Split into chunks
            List<String> chunks = splitIntoChunksWithContext(documentText, chunkSize);

            // Generate embeddings for all chunks
            List<float[]> embeddings = embeddingService.embed(chunks);

            // Create and save chunk entities
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = DocumentChunk.builder()
                    .document(document)
                    .chunkIndex(i)
                    .chunkText(chunks.get(i))
                    .embedding(embeddings.get(i))
                    .build();
                document.addChunk(chunk);
            }

            // Update document with chunk count and status
            document.setTotalChunks(chunks.size());
            document.setStatus(Document.DocumentStatus.PROCESSED);
            documentRepository.save(document);

            return document.getId();

        } catch (Exception e) {
            // Mark document as failed
            document.setStatus(Document.DocumentStatus.FAILED);
            documentRepository.save(document);
            throw e;
        }
    }
    
    @Transactional(readOnly = true)
    public List<String> findRelevantChunks(String question, int topK) throws TranslateException {
        long startTime = System.currentTimeMillis();
        System.out.println("[DOC] Starting chunk retrieval for topK=" + topK);

        // Check if any documents exist
        long documentCount = documentRepository.count();
        if (documentCount == 0) {
            throw new IllegalStateException("No document has been processed yet. Please upload a document first.");
        }

        // Generate embedding for question
        long embeddingStart = System.currentTimeMillis();
        float[] questionEmbedding = embeddingService.embed(question);
        long embeddingEnd = System.currentTimeMillis();
        System.out.println("[DOC] Question embedding generated in " + (embeddingEnd - embeddingStart) + " ms");

        String embeddingStr = formatVectorForPostgres(questionEmbedding);

        // Extract keywords for hybrid search
        long keywordStart = System.currentTimeMillis();
        List<String> keywords = extractKeywords(question.toLowerCase());
        String keywordStr = String.join(" ", keywords);
        long keywordEnd = System.currentTimeMillis();
        System.out.println("[DOC] Keywords extracted in " + (keywordEnd - keywordStart) + " ms: " + keywords);

        // Perform hybrid search with similarity threshold
        // Cosine distance < 0.75 means reasonably relevant (1.0 = completely dissimilar)
        double maxDistance = 0.75;

        long searchStart = System.currentTimeMillis();
        List<DocumentChunk> relevantChunks;
        if (keywords.isEmpty()) {
            // Pure semantic search if no keywords
            relevantChunks = chunkRepository.findMostSimilarChunks(embeddingStr, topK, maxDistance);
            System.out.println("[DOC] Using pure semantic search (threshold: " + maxDistance + ")");
        } else {
            // Hybrid search with ranked results
            relevantChunks = chunkRepository.findByHybridSearch(
                embeddingStr,
                keywordStr,
                topK * 2,  // More semantic candidates
                topK,      // Keyword candidates
                topK,      // Final limit
                maxDistance
            );
            System.out.println("[DOC] Using hybrid search (threshold: " + maxDistance + ")");
        }
        long searchEnd = System.currentTimeMillis();
        System.out.println("[DOC] Database search completed in " + (searchEnd - searchStart) + " ms, found " + relevantChunks.size() + " chunks");

        // Extract chunk texts
        List<String> chunkTexts = relevantChunks.stream()
            .map(DocumentChunk::getChunkText)
            .toList();

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("[DOC] Total chunk retrieval time: " + totalTime + " ms\n");

        return chunkTexts;
    }

    /**
     * Convert float array to PostgreSQL vector format: "[0.1,0.2,0.3]"
     */
    private String formatVectorForPostgres(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
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
        // Generic synonyms — works across any document domain
        return switch (word) {
            case "cost", "price" -> List.of("amount", "fee", "charge", "rate", "expense");
            case "start", "begin" -> List.of("commence", "initiate", "launch", "opening");
            case "end", "finish" -> List.of("complete", "close", "conclude", "terminate", "final");
            case "rule", "policy" -> List.of("guideline", "regulation", "procedure", "standard", "requirement");
            case "allow", "permit" -> List.of("eligible", "authorized", "approved", "granted");
            case "deny", "reject" -> List.of("refuse", "prohibit", "restrict", "disallow");
            case "deadline", "due" -> List.of("date", "timeline", "schedule", "period");
            case "process", "method" -> List.of("procedure", "step", "workflow", "approach");
            case "benefit", "advantage" -> List.of("feature", "perk", "reward", "incentive");
            case "issue", "problem" -> List.of("error", "bug", "defect", "concern", "challenge");
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
        // Keep track of the last paragraph added for overlap
        String lastParagraphOfPrevChunk = null;

        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;

            if (currentChunk.length() + paragraph.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                lastParagraphOfPrevChunk = getLastParagraph(currentChunk.toString());
                currentChunk = new StringBuilder();
                // Add overlap: prepend the last paragraph from the previous chunk
                if (lastParagraphOfPrevChunk != null && !lastParagraphOfPrevChunk.isEmpty()) {
                    currentChunk.append(lastParagraphOfPrevChunk).append("\n\n");
                }
                currentChunk.append(paragraph);
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

    private String getLastParagraph(String text) {
        String trimmed = text.trim();
        int lastBreak = trimmed.lastIndexOf("\n\n");
        if (lastBreak == -1) return trimmed;
        return trimmed.substring(lastBreak).trim();
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
    
    // Utility methods
    public int getDocumentChunkCount() {
        return (int) chunkRepository.count();
    }

    public boolean hasDocuments() {
        return documentRepository.count() > 0;
    }

    public List<String> getDocumentChunks() {
        return chunkRepository.findAll().stream()
            .map(DocumentChunk::getChunkText)
            .toList();
    }

    // Debug method to see what chunks contain specific keywords
    public List<String> debugSearchChunks(String keyword) {
        return chunkRepository.findAll().stream()
            .filter(chunk -> chunk.getChunkText().toLowerCase().contains(keyword.toLowerCase()))
            .map(DocumentChunk::getChunkText)
            .toList();
    }

    // Multi-document support methods

    // Get all documents
    public List<Document> getAllDocuments() {
        return documentRepository.findAllByOrderByUploadTimestampDesc();
    }

    // Get document by ID
    public Optional<Document> getDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    // Delete document
    @Transactional
    public void deleteDocument(Long id) {
        documentRepository.deleteById(id);
    }

    // Find chunks in specific document
    @Transactional(readOnly = true)
    public List<String> findRelevantChunksInDocument(Long documentId, String question, int topK)
            throws TranslateException {
        float[] questionEmbedding = embeddingService.embed(question);
        String embeddingStr = formatVectorForPostgres(questionEmbedding);

        List<DocumentChunk> chunks = chunkRepository.findMostSimilarChunksInDocuments(
            embeddingStr,
            List.of(documentId),
            topK
        );

        return chunks.stream()
            .map(DocumentChunk::getChunkText)
            .toList();
    }
}