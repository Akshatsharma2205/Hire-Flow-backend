# HireFlow AI — Production Deployment Guide

This guide details the deployment architecture, configuration parameters, and operational prerequisites for running HireFlow AI in production environments.

---

## 1. Runtime Environment & System Requirements

- **Operating System**: Linux (Ubuntu 22.04 LTS, Debian 12, Alpine, or RHEL 9 recommended)
- **Java Runtime**: **OpenJDK / Eclipse Temurin JDK 21 LTS**
- **Database**: **PostgreSQL 15+** (PostgreSQL 16 recommended)
- **Memory**: Minimum 2 GB RAM (4 GB recommended for production traffic with concurrent resume parsing)
- **CPU**: 2+ vCPUs recommended
- **Storage**: SSD-backed persistent disk for local document storage (or AWS S3 bucket)

---

## 2. Configuration & Required Environment Variables

HireFlow strictly follows Twelve-Factor App principles. In the `prod` profile, `StartupProfileGuard` validates all security parameters at boot and will fail fast if any weak or default placeholder secrets are detected.

| Variable Name | Required | Description | Example / Minimum |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | Active Spring profile | `prod` |
| `DATABASE_URL` | Yes | PostgreSQL JDBC URL | `jdbc:postgresql://postgres.internal:5432/hireflow` |
| `DATABASE_USERNAME` | Yes | Database username | `hireflow_app` |
| `DATABASE_PASSWORD` | Yes | Database password | Secure high-entropy password |
| `JWT_SECRET` | Yes | HMAC-SHA secret for JWT signing | Minimum 256 bits (32+ characters / base64) |
| `STORAGE_TYPE` | Yes | Storage provider implementation | `LOCAL` or `S3` |
| `LOCAL_STORAGE_BASE_PATH` | Conditional | Persistent directory path when `STORAGE_TYPE=LOCAL` | `/var/lib/hireflow/storage` |
| `AWS_REGION` | Conditional | AWS region when `STORAGE_TYPE=S3` | `us-east-1` |
| `S3_BUCKET` | Conditional | AWS S3 bucket name when `STORAGE_TYPE=S3` | `hireflow-resumes-prod` |
| `ENCRYPTION_KEY` | Yes | AES-256 master key for encrypted mailbox credentials | 32-byte hex or base64 key |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated allowed frontend origins | `https://hireflow.company.com` |

---

## 3. Production Packaging & Containerization

### 3.1 Build Executable JAR
```bash
cd hireflow-backend
./mvnw clean package -DskipTests --batch-mode
```
The resulting executable JAR will be located at:
`hireflow-backend/target/hireflow-0.0.1-SNAPSHOT.jar`

### 3.2 Standard Production Launch Command
```bash
java -server \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Dspring.profiles.active=prod \
  -jar target/hireflow-0.0.1-SNAPSHOT.jar
```

---

## 4. Database Setup & Flyway Migration

1. **Create PostgreSQL Database & Role**:
   ```sql
   CREATE USER hireflow_app WITH ENCRYPTED PASSWORD 'your_secure_password';
   CREATE DATABASE hireflow OWNER hireflow_app;
   GRANT ALL PRIVILEGES ON DATABASE hireflow TO hireflow_app;
   ```
2. **Schema Migrations**:
   Flyway runs automatically on application startup. It validates and executes:
   - `V1__init_schema.sql`: Core 27 domain tables
   - `V2__seed_data.sql`: Seed data (sanitized automatically in `prod` profile)
   - `V3__performance_indices.sql`: Production composite indices
   - `V4__webhook_security_and_idempotency.sql`: Webhook security & idempotency tables

---

## 5. Security Checklist Before Going Live

1. **Verify Startup Guard Validation**:
   - Inspect startup logs for confirmation that `StartupProfileGuard` validated secret entropy and rejected default development values.
2. **Verify Demo Credential Neutralization**:
   - `ProductionDemoCredentialSanitizer` runs on startup under `prod` profile, clearing default demo password hashes and disabling test organizations.
3. **Configure Reverse Proxy / TLS**:
   - Place HireFlow behind an HTTPS reverse proxy (Nginx, AWS ALB, Caddy, Cloudflare).
   - Ensure `X-Forwarded-For` and `X-Forwarded-Proto` headers are correctly forwarded.
4. **CORS Restrictions**:
   - Do not use `*` for CORS in production. Configure `CORS_ALLOWED_ORIGINS` with explicit domains.
