# SecuHub Backend

보안 운영 통합 관리 플랫폼 - 백엔드 API 서버

## 기술 스택

- Java 21 (LTS)
- Spring Boot 3.4.1
- Spring Security + JWT
- Spring Data JPA + MariaDB
- Apache POI (엑셀 처리)
- Gradle 8.11

## 프로젝트 구조

```
secuhub-backend/
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/secuhub/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       └── application-prod.yml
│       └── test/
├── gradle/wrapper/
├── settings.gradle
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
./gradlew bootRun
```

### 3. 접속

- API 서버: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

## 빌드 및 배포

외부망에서 JAR을 빌드하고, 폐쇄망 서버에서 실행하는 구조입니다.

```bash
# 외부망 - 빌드
./gradlew bootJar
# 결과: app/build/libs/secuhub.jar

# 폐쇄망 - 실행
java -jar secuhub.jar --spring.profiles.active=prod
```
