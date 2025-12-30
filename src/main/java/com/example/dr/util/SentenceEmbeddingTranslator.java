package com.example.dr.util;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.io.IOException;
import java.nio.file.Paths;

public class SentenceEmbeddingTranslator implements Translator<String, float[]> {
    
    private HuggingFaceTokenizer tokenizer;
    
    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        // Load tokenizer from model directory
        String modelPath = ctx.getModel().getModelPath().toString();
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(Paths.get(modelPath, "tokenizer.json"))
                .build();
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        // Tokenize the input text
        Encoding encoding = tokenizer.encode(input);
        
        NDManager manager = ctx.getNDManager();
        
        // Get token IDs
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();
        long[] tokenTypeIds = encoding.getTypeIds();
        
        // Convert to NDArrays with proper shapes
        NDArray inputIdsArray = manager.create(inputIds).expandDims(0); // Shape: [1, seq_len]
        NDArray attentionMaskArray = manager.create(attentionMask).expandDims(0); // Shape: [1, seq_len]
        NDArray tokenTypeIdsArray = manager.create(tokenTypeIds).expandDims(0); // Shape: [1, seq_len]
        
        // Return NDList with the exact input names expected by the model
        NDList inputs = new NDList();
        inputs.add(inputIdsArray);      // input_ids
        inputs.add(attentionMaskArray); // attention_mask  
        inputs.add(tokenTypeIdsArray);  // token_type_ids
        
        return inputs;
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // The model typically outputs embeddings in the first element
        // For sentence transformers, we usually take the mean pooling of the last hidden state
        NDArray embeddings = list.get(0); // Shape: [1, seq_len, hidden_size]
        
        // Perform mean pooling to get sentence embedding
        // Take mean across sequence dimension (dimension 1)
        NDArray pooled = embeddings.mean(new int[]{1}); // Shape: [1, hidden_size]
        
        // Convert to float array and squeeze the batch dimension
        return pooled.squeeze(0).toFloatArray(); // Shape: [hidden_size]
    }

    @Override
    public Batchifier getBatchifier() {
        return null; // We'll handle batching manually if needed
    }
}