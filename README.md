# URL Shortener Backend

A simple URL shortener service built using Spring Boot with Redis caching and rate limiting.

---

## 🚀 Prerequisites

Make sure the following are installed:

* Java 17+
* Maven
* Docker & Docker Compose

---

## 🐳 Start Required Services

This project uses Redis. Start it using Docker:

```bash
docker-compose up -d
```

To verify:

```bash
docker ps
```

---

## 🛠️ Build the Project

```bash
mvn clean install
```

---

## ▶️ Run the Application

### Option 1: Using Maven

```bash
mvn spring-boot:run
```

### Option 2: Using JAR

```bash
java -jar target/<your-jar-name>.jar
```

---

## 🌐 Application URLs

* Base URL:
  http://localhost:8080

* Swagger UI (if enabled):
  http://localhost:8080/swagger-ui/

---

## 📌 API Usage

### 1. Create Short URL

```bash
curl -X POST http://localhost:8080/api/url/shorten \
-H "Content-Type: application/json" \
-d '{"originalUrl":"https://google.com"}'
```

---

### 2. Redirect to Original URL

Open in browser:

```
http://localhost:8080/{shortCode}
```

Example:

```
http://localhost:8080/abc123
```

---

## ⚠️ Notes

* Do not open `http://localhost:8080/` directly (no frontend is configured).
* Ensure Redis is running before starting the application.
* Port used: **8080**

---

## 🧪 Troubleshooting

### Redis not connected

```bash
docker ps
```

### Port already in use

```bash
lsof -i :8080
kill -9 <PID>
```

### Build issues

```bash
mvn clean install -U
```

---

## 📦 Tech Stack

* Spring Boot
* Redis
* Maven
* Docker

---

## 👨‍💻 Author

Meet Sinojia
