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

@Service
public class EmbeddingService {

    private final Map<String, Predictor<String, float[]>> predictors = new LinkedHashMap<>();
    private Predictor<String, float[]> currentPredictor;
    private String currentModelName;

    private static final Map<String, String> MODEL_URLS = Map.of(
        "all-MiniLM-L6-v2", "sentence-transformers/all-MiniLM-L6-v2",
        "all-MiniLM-L12-v2", "sentence-transformers/all-MiniLM-L12-v2",
        "multi-qa-MiniLM-L6-cos-v1", "sentence-transformers/multi-qa-MiniLM-L6-cos-v1"
    );

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
        // set default
        currentModelName = "all-MiniLM-L6-v2";
        currentPredictor = predictors.get(currentModelName);
    }

    public List<String> getAvailableModels() {
        return new ArrayList<>(MODEL_URLS.keySet());
    }

    public String getCurrentModel() {
        return currentModelName;
    }

    public void setCurrentModel(String name) {
        if (!predictors.containsKey(name)) {
            throw new IllegalArgumentException("Model not found: " + name);
        }
        currentModelName = name;
        currentPredictor = predictors.get(name);
    }

    public float[] embed(String text) throws TranslateException {
        return currentPredictor.predict(text);
    }

    public List<float[]> embed(List<String> texts) throws TranslateException {
        List<float[]> list = new ArrayList<>();
        for (String t : texts) {
            list.add(embed(t));
        }
        return list;
    }
}