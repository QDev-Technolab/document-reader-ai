# Spring Boot REST API with Ollama Integration

This is a **Spring Boot REST API** project that integrates with the **Ollama server** to run machine learning models for tasks such as sentence embeddings and semantic search.

## ✨ Supported Models

The application supports the following sentence embedding models, which must be downloaded in ONNX format:

- `all-MiniLM-L6-v2`
- `all-MiniLM-L12-v2`
- `multi-qa-MiniLM-L6-cos-v1`

Each model must include a `model.onnx` file which you can download from the respective Hugging Face repositories:

| Model Name | Hugging Face ONNX Link |
|------------|-------------------------|
| **all-MiniLM-L6-v2** | [Download](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main/onnx) |
| **all-MiniLM-L12-v2** | [Download](https://huggingface.co/sentence-transformers/all-MiniLM-L12-v2/tree/main/onnx) |
| **multi-qa-MiniLM-L6-cos-v1** | [Download](https://huggingface.co/sentence-transformers/multi-qa-MiniLM-L6-cos-v1/tree/main/onnx) |

> ✅ Place the downloaded `model.onnx` files in your application's designated model directory (e.g. `src/main/resources/sentence-transformers/$modelname` or as configured).

---

## ⚙️ Prerequisites

Before running the application, make sure the following are installed:

- **Java 21**
- **Maven**
- **Ollama** (local LLM server)

---

## 🚀 Ollama Setup Instructions

This application requires a running instance of the [Ollama](https://ollama.com) server to interface with LLMs such as `llama3` and `gemma3`.

### 1. Install Ollama (Windows)

```bash
winget install Ollama.Ollama
```

> You may need to restart your terminal or machine after installation.

### 2. Pull and Run Required Models

```bash
ollama pull llama3:8b
ollama run gemma3
```

- `llama3:8b` is used for general LLM inference.
- `gemma3` might be used for specific lightweight tasks or responses.

> Make sure Ollama is running before starting the Spring Boot application.

---

## 🧪 Project Setup & Run

### 1. Build the project

```bash
mvn clean install
```

### 2. Run the Spring Boot Application

```bash
mvn spring-boot:run
```

The application should now start on:

```
http://localhost:8080
```

---

## 📂 API Endpoints

> Add your specific API endpoints here. Example:

- `GET /api/embedding?model=all-MiniLM-L6-v2&text=hello world`
- `POST /api/query`

---

## 🧠 Additional Notes

- Ensure Ollama server and the required models are running **before hitting the APIs**.
- Make sure you download and correctly place the `model.onnx` files for each transformer model in your resource/model directory.
- This application uses sentence transformer models for efficient and fast embedding.

---

## 📝 License

This project is licensed under the MIT License.
