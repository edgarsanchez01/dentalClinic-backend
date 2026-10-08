# DentalClinic Backend - Desarrollo local

## Requisitos
- Java 21 (LTS)
- Maven 3.9+
- Docker + Docker Compose (para PostgreSQL local)
- PostgreSQL 16 (o usar docker-compose.dev.yml)

## Arrancar PostgreSQL
```bash
docker compose -f docker-compose.dev.yml up -d db
```

El puerto en el host se parametriza con `POSTGRES_HOST_PORT` (fallback `5434`); dentro del
contenedor PostgreSQL siempre escucha en `5432`. Si lo cambias, el puerto de `DB_URL` debe
coincidir (`backend/.env.example`).

## Compilar y ejecutar
```bash
cd backend
mvn clean compile
DB_URL=jdbc:postgresql://localhost:5434/dentalclinic_dev \
  mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Health
```bash
curl http://localhost:8080/actuator/health
```

## Construir JAR
```bash
mvn clean package
```

## Notas
- En `dev`: `ddl-auto=create-drop`. La base se crea al arrancar.
- En `prod`: `ddl-auto=none`. El esquema no es manipulado.
- Sin Flyway ni Liquibase: ver `db/schema/README.md`.
- Solo infraestructura técnica en Fase 1.