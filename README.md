# Document Reader AI — Spring Boot + Ollama + pgvector

A **Spring Boot REST API** that lets you upload documents (PDF, DOCX, TXT) and chat with them using local LLMs via **Ollama** and semantic search powered by **pgvector**.  
It includes a built-in **ChatGPT-style web UI** accessible directly from the browser, plus a full **Swagger UI** for API exploration.

---

## ✨ Features

- 📄 Upload PDF, DOCX, TXT, or Excel (XLSX/XLS) documents
- 🧠 Semantic search using ONNX sentence-transformer embeddings (via DJL)
- 💬 Streaming & non-streaming Q&A using a local Ollama LLM
- 🗂️ Multi-document support with per-document querying
- 🔀 Branching conversation history (edit & regenerate messages)
- 🌐 Built-in dark-mode chat web UI at `http://localhost:8080`
- 📖 Swagger UI at `http://localhost:8080/swagger-ui.html`
- 🔍 Hybrid search (semantic + keyword) for better retrieval accuracy

---

## ✨ Supported Embedding Models

The application supports the following sentence embedding models in ONNX format.  
Each model folder must contain **three files**: `model.onnx`, `config.json`, and `tokenizer.json`.

| Model Name | Hugging Face ONNX Link |
|------------|------------------------|
| **all-MiniLM-L6-v2** | [Download](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main/onnx) |
| **all-MiniLM-L12-v2** | [Download](https://huggingface.co/sentence-transformers/all-MiniLM-L12-v2/tree/main/onnx) |
| **multi-qa-MiniLM-L6-cos-v1** | [Download](https://huggingface.co/sentence-transformers/multi-qa-MiniLM-L6-cos-v1/tree/main/onnx) |

> ✅ Place all three files for each model in the corresponding directory:
> ```
> src/main/resources/sentence-transformers/<model-name>/
>   ├── model.onnx
>   ├── config.json
>   └── tokenizer.json
> ```

The **default model is `all-MiniLM-L6-v2`**. You can switch models at runtime via the `/chat/set-model` API or from the Swagger UI.

---

## ⚙️ Prerequisites

Before running the application, make sure the following are installed:

- **Java 21**
- **Maven**
- **PostgreSQL 16** (with pgvector extension)
- **Ollama** (local LLM server)

---

## 🗄️ PostgreSQL Setup Instructions

This application uses **PostgreSQL** with the **pgvector** extension for vector similarity search.  
Database schema is **automatically managed by Flyway** — no manual SQL setup needed beyond creating the database.

### 1. Install PostgreSQL

#### Windows

1. Download the PostgreSQL installer from the official website:  
   https://www.postgresql.org/download/windows/

2. Run the installer and follow the setup wizard:
   - Choose PostgreSQL **16** (or latest)
   - Set a password for the `postgres` user (default used by this app: `postgres123`)
   - Keep the default port **5432**
   - Select the **Stack Builder** option when prompted (needed for pgvector)

3. Alternatively, install via **winget**:

```powershell
winget install PostgreSQL.PostgreSQL.16
```

> After installation, ensure `C:\Program Files\PostgreSQL\16\bin` is added to your system **PATH** so you can use `psql` from the terminal.

#### Ubuntu/Linux

```bash
sudo apt update
sudo apt install postgresql postgresql-contrib
```

### 2. Install pgvector Extension

#### Windows

1. Download the prebuilt pgvector package for Windows from:  
   https://github.com/pgvector/pgvector/releases

2. Extract and copy the files to your PostgreSQL installation directory:
   - Copy `vector.dll` to `C:\Program Files\PostgreSQL\16\lib`
   - Copy `vector.control` and `vector--*.sql` files to `C:\Program Files\PostgreSQL\16\share\extension`

3. Alternatively, if you have **Visual Studio Build Tools** installed, you can build from source:

```powershell
# Clone pgvector
git clone https://github.com/pgvector/pgvector.git
cd pgvector

# Set PostgreSQL path
set "PGROOT=C:\Program Files\PostgreSQL\16"

# Build and install
nmake /F Makefile.win
nmake /F Makefile.win install
```

#### Ubuntu/Linux

```bash
# Install pgvector for PostgreSQL 16
sudo apt install postgresql-16-pgvector
```

### 3. Start PostgreSQL Service

#### Windows

PostgreSQL is registered as a Windows service and starts automatically after installation. To manage it manually:

```powershell
# Check service status
sc query postgresql-x64-16

# Start the service
net start postgresql-x64-16

# Stop the service
net stop postgresql-x64-16
```

Or use **Services** (`services.msc`) to start/stop the `postgresql-x64-16` service.

#### Ubuntu/Linux

```bash
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

### 4. Create Database and Enable pgvector

#### Windows

Open **Command Prompt** or **PowerShell** and run:

```powershell
psql -U postgres
```

Enter the password you set during installation when prompted.

#### Ubuntu/Linux

```bash
# Access PostgreSQL as the postgres user
sudo -u postgres psql
```

#### Run the following SQL commands (both Windows and Linux):

```sql
-- Create the database
CREATE DATABASE document_reader_ai;

-- Connect to the new database
\c document_reader_ai

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Set password for postgres user (if not already set)
ALTER USER postgres WITH PASSWORD 'postgres123';

-- Exit
\q
```

> **Note:** The default configuration uses:
> - Database: `document_reader_ai`
> - Username: `postgres`
> - Password: `postgres123`
> - Host: `localhost`
> - Port: `5432`
>
> You can modify these in `src/main/resources/application.properties`

> **ℹ️ Flyway Auto-Migration:** The full database schema (tables, indexes, HNSW vector index) is **automatically created by Flyway on first startup**. You only need to create the database and enable the `vector` extension manually.

#### Windows Troubleshooting

- **`psql` not found**: Add `C:\Program Files\PostgreSQL\16\bin` to your system PATH
- **Connection refused**: Ensure the PostgreSQL service is running (`net start postgresql-x64-16`)
- **pgvector not loading**: Verify that `vector.dll` is in the correct `lib` directory and restart PostgreSQL
- **Permission denied**: Run your terminal as **Administrator** when installing pgvector files

---

## 🚀 Ollama Setup Instructions

This application requires a running instance of the [Ollama](https://ollama.com) server with a **custom reasoning model** built from `llama3.2:3b`.

### 1. Install Ollama

#### Windows

```bash
winget install Ollama.Ollama
```

> You may need to restart your terminal or machine after installation.

#### Ubuntu/Linux

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

> The installation script will automatically download and set up Ollama on your system.

### 2. Pull Base Model and Build the Custom Reasoning Model

```bash
# Step 1: Pull the base model
ollama pull llama3.2:3b

# Step 2: Build the custom reasoning model used by this application
ollama create llama3.2-reasoning -f ollama/Modelfile-reasoning
```

> The `ollama/Modelfile-reasoning` file in this project configures the model with an optimised system prompt and inference parameters for precise document analysis (low temperature, structured reasoning).

The application uses **`llama3.2-reasoning`** (configured in `application.properties` under `llm.model`).

> ⚠️ Make sure Ollama is running **before** starting the Spring Boot application.

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

The application will start on:

```
http://localhost:8080
```

---

## 🌐 Accessing the Application

| Interface | URL |
|-----------|-----|
| **Web Chat UI** (dark-mode ChatGPT-style) | http://localhost:8080 |
| **Swagger UI** (interactive API explorer) | http://localhost:8080/swagger-ui.html |

---

## 📂 API Endpoints

All endpoints are grouped under two controllers. Full interactive documentation is available at **Swagger UI** (`/swagger-ui.html`).

### Chat Controller (`/chat`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/chat/upload` | Upload a document (PDF, DOCX, TXT, XLSX, XLS). Max file size: **50 MB** |
| `POST` | `/chat/ask` | Ask a question — auto-optimised response length |
| `POST` | `/chat/ask-styled` | Ask with explicit style (`short` / `normal` / `detailed`) |
| `POST` | `/chat/ask-detailed` | Ask and receive answer + the source chunks used |
| `POST` | `/chat/ask-stream` | Streaming SSE response (token-by-token, ChatGPT-style) |
| `GET` | `/chat/status` | Check Ollama and embedding model status |
| `GET` | `/chat/models` | List available embedding models and current LLM models |
| `POST` | `/chat/set-model` | Switch the active embedding model |
| `GET` | `/chat/documents` | List all uploaded documents with metadata |
| `DELETE` | `/chat/documents/{id}` | Delete a document and all its chunks |
| `POST` | `/chat/documents/{id}/ask` | Ask a question scoped to a specific document |
| `GET` | `/chat/conversations` | List all conversations |
| `GET` | `/chat/conversations/{id}/thread` | Get the latest visible message thread |
| `GET` | `/chat/conversations/{id}/thread-from/{msgId}` | Get thread starting from a specific message |
| `GET` | `/chat/conversations/{id}/messages/{msgId}/siblings` | Get sibling (alternative) versions of a message |
| `DELETE` | `/chat/conversations/{id}` | Delete a conversation |

### Debug Controller (`/debug`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/debug/search-chunks?keyword=...` | Find all document chunks containing a keyword |
| `GET` | `/debug/all-chunks` | Retrieve all stored document chunks |
| `GET` | `/debug/find-timing` | Find chunks containing time/schedule-related content |

---

## 🧠 Additional Notes

- Ensure Ollama is running with the `llama3.2-reasoning` model **before hitting the APIs**.
- Each embedding model folder must contain **`model.onnx`**, **`config.json`**, and **`tokenizer.json`** — all three files are required.
- The application uses a **hybrid search** strategy (combining semantic vector similarity and keyword full-text search) for improved retrieval accuracy.
- **File upload limit**: 50 MB per file (`spring.servlet.multipart.max-file-size`).
- Database schema migrations (tables, indexes, HNSW index) are handled automatically by **Flyway** on startup.
- The default embedding model is `all-MiniLM-L6-v2`. You can switch at runtime using `POST /chat/set-model?model=multi-qa-MiniLM-L6-cos-v1`.
- Conversation history supports **branching** — you can edit past messages and regenerate responses, creating alternative conversation branches (like ChatGPT's edit feature).

---

## 📝 License

This project is licensed under the MIT License.
