# URL Shortener

A production-grade distributed URL shortener built with **Java Spring Boot**, **Redis** caching, and **PostgreSQL** persistence.

## Architecture

```
Client
  │
  ▼
RateLimitFilter (token bucket per IP via Redis)
  │
  ▼
UrlController / AnalyticsController
  │
  ▼
UrlService (cache-aside pattern)
  ├── Redis  ← cache hit (fast path, ~1ms)
  └── PostgreSQL ← cache miss (fallback, ~5ms)
```

### Cache-aside flow
1. `GET /{shortCode}` hits Redis first
2. **Cache hit** → return URL + increment click counter (atomic INCR)
3. **Cache miss** → query PostgreSQL → re-populate Redis with TTL → return URL

### Rate limiting
Token bucket implemented in Redis per client IP:
- Default: 10 requests per 60 seconds per IP
- Exceeding limit returns `HTTP 429`
- Configurable via `application.yml`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2 |
| Cache | Redis 7 (cache-aside + token bucket) |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Docs | Springdoc OpenAPI (Swagger UI) |
| Containerization | Docker, Docker Compose |
| Testing | JUnit 5, Mockito, @WebMvcTest |

---

## API Endpoints

### Shorten a URL
```
POST /api/shorten
Content-Type: application/json

{
  "originalUrl": "https://example.com/very/long/path",
  "expiryHours": 48
}
```

Response `201 Created`:
```json
{
  "shortCode": "abcdef",
  "shortUrl": "http://localhost:8080/abcdef",
  "originalUrl": "https://example.com/very/long/path",
  "createdAt": "2024-01-01T10:00:00",
  "expiresAt": "2024-01-03T10:00:00"
}
```

### Redirect
```
GET /{shortCode}
→ HTTP 302 Location: https://example.com/very/long/path
```

### Analytics
```
GET /api/analytics/{shortCode}
```

Response `200 OK`:
```json
{
  "shortCode": "abcdef",
  "originalUrl": "https://example.com/very/long/path",
  "totalClicks": 42,
  "createdAt": "2024-01-01T10:00:00",
  "expiresAt": "2024-01-03T10:00:00"
}
```

### Deactivate
```
DELETE /api/urls/{shortCode}
→ HTTP 204 No Content
```

---

## Running Locally

### Prerequisites
- Docker & Docker Compose
- Java 17+ (for local development without Docker)

### With Docker Compose (recommended)
```bash
git clone https://github.com/MeetSinojia/url-shortener
cd url-shortener

docker-compose up --build
```

App runs at `http://localhost:8080`
Swagger UI at `http://localhost:8080/swagger-ui.html`

### Without Docker
```bash
# Start dependencies
docker run -d -p 5432:5432 -e POSTGRES_DB=urlshortener -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16-alpine
docker run -d -p 6379:6379 redis:7-alpine

# Run app
./mvnw spring-boot:run
```

---

## Running Tests
```bash
./mvnw test
```

Tests use H2 in-memory database and embedded Redis — no external dependencies required.

---

## Project Structure

```
src/main/java/com/urlshortener/
├── controller/        # REST endpoints
├── service/           # Business logic + Base62 encoding
├── repository/        # JPA repository (PostgreSQL)
├── cache/             # Redis caching + rate limiting
├── middleware/        # Request logging + rate limit filter
├── model/             # Entities + DTOs
├── exception/         # Custom exceptions + global handler
└── config/            # App config + Swagger

src/main/resources/
├── application.yml
├── application-test.yml
└── db/migration/      # Flyway SQL migrations
```

---

## Configuration

Key settings in `application.yml`:

```yaml
app:
  base-url: http://localhost:8080
  cache:
    url-ttl-hours: 24        # How long URLs stay in Redis
    click-count-ttl-hours: 168
  rate-limit:
    capacity: 10             # Max requests per window
    window-seconds: 60       # Window duration
```
