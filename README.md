# SEMS (SecuHub)

보안 운영 통합 관리 플랫폼

## 기술 스택

### Backend
- Java 21 (LTS)
- Spring Boot 3.4.1
- Spring Security + JWT
- Spring Data JPA + MariaDB
- Apache POI (엑셀 처리)
- Gradle 8.11

### Frontend
- Vue.js 3
- Vite
- TypeScript

## 프로젝트 구조

```
SEMS/
├── backend/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/secuhub/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       ├── application-prod.yml
│       │       └── static/            ← Vue 빌드 결과물 (자동 복사)
│       └── test/
├── frontend/
│   ├── build.gradle
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── build.gradle                       ← 루트 (공통 플러그인 선언)
├── settings.gradle
├── gradle/wrapper/
├── gradlew
└── gradlew.bat
```

## 시작하기

### 1. MariaDB 데이터베이스 준비

```sql
CREATE DATABASE secuhub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secuhub'@'localhost' IDENTIFIED BY 'secuhub_dev_password';
GRANT ALL PRIVILEGES ON secuhub.* TO 'secuhub'@'localhost';
FLUSH PRIVILEGES;
```

### 2. 개발 서버 실행

```bash
# 백엔드 실행 (localhost:8080)
./gradlew :backend:bootRun

# 프론트엔드 개발 서버 (localhost:5173, API → 8080 프록시)
cd frontend && npm run dev
```

### 3. 접속

- 프론트엔드 개발 서버: http://localhost:5173
- API 서버: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

## 빌드 및 배포

프론트엔드 빌드 → static/ 복사 → JAR 패키징이 한 번에 실행됩니다.

```bash
# 전체 빌드 (Vue 빌드 + Spring Boot JAR)
./gradlew :backend:build
# 결과: backend/build/libs/secuhub.jar

# 폐쇄망 서버에서 실행
java -jar secuhub.jar --spring.profiles.active=prod
```

빌드된 JAR 하나로 SPA와 API를 함께 서빙합니다.