package com.example.dr.service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.dr.util.SentenceEmbeddingTranslator;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;

/**
 * Service that loads ONNX sentence-transformer models via DJL and generates vector embeddings for text.
 * Three models are loaded at startup; the active model defaults to {@code multi-qa-MiniLM-L6-cos-v1},
 * which is purpose-built for semantic search and Q&A retrieval tasks.
 */
@Service
public class EmbeddingService {

    /** Map of model name → DJL Predictor, keyed in insertion order. */
    private final Map<String, Predictor<String, float[]>> predictors = new LinkedHashMap<>();

    /** The currently active predictor used for all embed calls. */
    private Predictor<String, float[]> currentPredictor;

    /** Name of the currently active embedding model. */
    private String currentModelName;

    /** Supported model names mapped to their classpath resource paths. */
    private static final Map<String, String> MODEL_URLS = Map.of(
            "all-MiniLM-L6-v2", "sentence-transformers/all-MiniLM-L6-v2",
            "all-MiniLM-L12-v2", "sentence-transformers/all-MiniLM-L12-v2",
            "multi-qa-MiniLM-L6-cos-v1", "sentence-transformers/multi-qa-MiniLM-L6-cos-v1");

    /**
     * Loads all ONNX models from the classpath at application startup and sets the default predictor.
     *
     * @throws Exception if any model file cannot be loaded
     */
    @PostConstruct
    public void init() throws Exception {
        for (var entry : MODEL_URLS.entrySet()) {
            String name = entry.getKey();
            String repo = entry.getValue();
            var criteria = Criteria.builder()
                    .optEngine("OnnxRuntime")
                    .setTypes(String.class, float[].class)
                    .optModelPath(Paths.get(ClassLoader.getSystemResource(repo + "/model.onnx").toURI()))
                    .optProgress(new ProgressBar())
                    .optTranslator(new SentenceEmbeddingTranslator())
                    .build();
            Model model = criteria.loadModel();
            predictors.put(name, model.newPredictor(new SentenceEmbeddingTranslator()));
        }
        // multi-qa is trained on 215M question-answer pairs — better retrieval accuracy than general-purpose models
        currentModelName = "multi-qa-MiniLM-L6-cos-v1";
        currentPredictor = predictors.get(currentModelName);
    }

    /**
     * @return name of the currently active embedding model
     */
    public String getCurrentModel() {
        return currentModelName;
    }

    /**
     * @param text the input text to embed
     * @return 384-dimensional float embedding vector
     * @throws TranslateException if the model inference fails
     */
    public float[] embed(String text) throws TranslateException {
        return currentPredictor.predict(text);
    }

    /**
     * @param texts list of input texts to embed
     * @return list of 384-dimensional float embedding vectors in the same order
     * @throws TranslateException if any model inference fails
     */
    public List<float[]> embed(List<String> texts) throws TranslateException {
        List<float[]> list = new ArrayList<>();
        for (String t : texts) {
            list.add(embed(t));
        }
        return list;
    }
}
