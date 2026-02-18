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
- **PostgreSQL 16** (with pgvector extension)
- **Ollama** (local LLM server)

---

## 🗄️ PostgreSQL Setup Instructions

This application uses **PostgreSQL** with the **pgvector** extension for vector similarity search.

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

#### Windows Troubleshooting

- **`psql` not found**: Add `C:\Program Files\PostgreSQL\16\bin` to your system PATH
- **Connection refused**: Ensure the PostgreSQL service is running (`net start postgresql-x64-16`)
- **pgvector not loading**: Verify that `vector.dll` is in the correct `lib` directory and restart PostgreSQL
- **Permission denied**: Run your terminal as **Administrator** when installing pgvector files

---

## 🚀 Ollama Setup Instructions

This application requires a running instance of the [Ollama](https://ollama.com) server to interface with LLMs such as `llama3` and `gemma3`.

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

### 2. Pull and Run Required Models

```bash
ollama pull llama3.2-reasoning
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
