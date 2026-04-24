---

# URL Shortener Backend

A scalable URL shortener built using Spring Boot with:

* PostgreSQL **sharding + read replicas**
* Redis **caching + LFU eviction**
* Rate limiting
* Load balancing ready (NGINX)

---

# 🚀 Prerequisites

Make sure you have:

* Java 17+
* Maven
* Docker
* Redis (via Docker)

---

# 🧠 System Overview

```text
Client
  ↓
Spring Boot App
  ↓
Shard Router (based on shortCode)
  ↓
Primary DB (writes) / Replica DB (reads)
  ↓
Redis Cache (fast access)
```

---

# 🐳 Step 1: Start Redis

```bash
docker run -d \
  --name redis \
  -p 6379:6379 \
  redis
```

Verify:

```bash
docker ps
```

---

# 🐘 Step 2: Start PostgreSQL (Sharding + Replication)

We use **2 shards**, each with:

* 1 Primary (write)
* 1 Replica (read)

---

## 🔹 Create Docker Network

```bash
docker network create pg-network
```

---

## 🔹 Shard 0

### Primary (Port 5436)

```bash
docker run -d \
  --name pg-primary-0 \
  --network pg-network \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRESQL_REPLICATION_MODE=master \
  -e POSTGRESQL_REPLICATION_USER=repl_user \
  -e POSTGRESQL_REPLICATION_PASSWORD=repl_pass \
  -p 5436:5432 \
  bitnami/postgresql
```

### Create Database

```bash
psql -h localhost -p 5436 -U postgres
```

```sql
CREATE DATABASE urlshortener_shard0;
```

---

### Replica (Port 5437)

```bash
docker run -d \
  --name pg-replica-0 \
  --network pg-network \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRESQL_REPLICATION_MODE=slave \
  -e POSTGRESQL_MASTER_HOST=pg-primary-0 \
  -e POSTGRESQL_REPLICATION_USER=repl_user \
  -e POSTGRESQL_REPLICATION_PASSWORD=repl_pass \
  -p 5437:5432 \
  bitnami/postgresql
```

⏳ Wait ~15–30 seconds for replication to initialize

---

## 🔹 Shard 1

### Primary (Port 5434)

```bash
docker run -d \
  --name pg-primary-1 \
  --network pg-network \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRESQL_REPLICATION_MODE=master \
  -e POSTGRESQL_REPLICATION_USER=repl_user \
  -e POSTGRESQL_REPLICATION_PASSWORD=repl_pass \
  -p 5434:5432 \
  bitnami/postgresql
```

### Create Database

```bash
psql -h localhost -p 5434 -U postgres
```

```sql
CREATE DATABASE urlshortener_shard1;
```

---

### Replica (Port 5435)

```bash
docker run -d \
  --name pg-replica-1 \
  --network pg-network \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRESQL_REPLICATION_MODE=slave \
  -e POSTGRESQL_MASTER_HOST=pg-primary-1 \
  -e POSTGRESQL_REPLICATION_USER=repl_user \
  -e POSTGRESQL_REPLICATION_PASSWORD=repl_pass \
  -p 5435:5432 \
  bitnami/postgresql
```

⏳ Wait ~15–30 seconds

---

# ⚙️ Database Configuration

```yaml
shard0:
  primary-url: jdbc:postgresql://localhost:5436/urlshortener_shard0
  replica-url: jdbc:postgresql://localhost:5437/urlshortener_shard0

shard1:
  primary-url: jdbc:postgresql://localhost:5434/urlshortener_shard1
  replica-url: jdbc:postgresql://localhost:5435/urlshortener_shard1
```

---

# 🧠 How DB Routing Works

* Hash of `shortCode` → decides shard (0 or 1)
* Writes → go to **primary**
* Reads → go to **replica** (`@Transactional(readOnly = true)`)

---

# 🛠️ Build the Project

```bash
mvn clean install
```

---

# ▶️ Run the Application

```bash
mvn spring-boot:run
```

---

# 🌐 Application URLs

* Base:
  [http://localhost:8080](http://localhost:8080)

* Swagger:
  [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

---

# 📌 API Usage

---

## 🔹 Create Short URL

```bash
curl -X POST http://localhost:8080/api/shorten \
-H "Content-Type: application/json" \
-d '{"originalUrl":"https://google.com"}'
```

---

## 🔹 Redirect

```text
http://localhost:8080/{shortCode}
```

---

## 🔹 Analytics

```bash
curl http://localhost:8080/api/analytics/{shortCode}
```

---

# 🧠 Redis Usage

We store:

```text
url:{shortCode}     → original URL (TTL 24h)
clicks:{shortCode}  → click count (TTL 7 days)
rate:{ip}           → rate limiting (TTL 60s)
```

---

# 🔥 Cache Strategy

* Eviction Policy: `volatile-lfu`
* Keeps frequently accessed URLs longer
* Removes least-used keys when memory is full

---

# ⚠️ Important Notes

* Always start DB + Redis **before app**
* Wait for replicas to fully initialize
* Ports used:

```text
Redis → 6379
App   → 8080

Shard0:
  5436 (primary)
  5437 (replica)

Shard1:
  5434 (primary)
  5435 (replica)
```

---

# 🧪 Troubleshooting

---

## DB Connection Issues

```bash
docker ps
docker logs pg-replica-0
```

---

## Redis Issues

```bash
docker ps
```

---

## Port Conflicts

```bash
lsof -i :5432
```

---

## Replica Not Ready

👉 Wait for:

```text
database system is ready to accept connections
```

---

# 📦 Tech Stack

* Spring Boot
* PostgreSQL (Sharding + Replication)
* Redis (LFU cache)
* Docker

---

# 👨‍💻 Author

Meet Sinojia

---
