# SecuHub 프로젝트 기획서 v11.0

## 보안 운영 통합 관리 플랫폼

---

## 0. 버전 이력

### v11.0 (2026-04-22) — 증빙 수집 협업 체계 정립
**v10 → v11 주요 변경:**
- ✅ **Framework 상속 모델 도입** — `frameworks.parent_framework_id` (self-FK) 추가. 이번 감사 주기(예: ISMS-P 2026)를 이전 년도 기반으로 손쉽게 생성
- ✅ **증빙 승인/반려 플로우 도입** — `evidence_files.review_status` + `reviewed_by` + `review_note` + `reviewed_at` 추가. 반려 사유 필수
- ✅ **담당자 협업 구조 도입** — `evidence_types.owner_user_id` 활용. 다수 팀(인사팀, 법무팀 등)이 각자 담당 증빙을 업로드하고 관리자가 검토하는 구조
- ✅ **담당자용 "내 할 일" 페이지** 추가 — 사이드바 별도 메뉴
- ✅ **이메일 기반 알림** (JavaMailSender) — 인앱 알림 벨 없음, "내 할 일" 대시보드가 그 역할
- ✅ **통제 항목 진입 = Framework 목록 페이지** — 최근 작업 중 카드 + 전체 Framework 테이블 + 검토 배지
- ❌ **갱신/만료 개념 제거** — `evidence_types.renewal_cycle_days`, `evidence_files.valid_until` 폐기. ISMS-P 실무와 맞지 않음. Framework 분리로 "이번 감사 주기" 개념 대체
- 🔧 **권한 모델 유지** — 역할 ENUM(admin/approver/developer) 변경 없음. `permission_evidence` boolean + `owner_user_id`로 세분 제어

### v10.0 (이전)
- 통제 항목 중심 허브화 시도
- `owner_user_id`, `renewal_cycle_days`, `valid_until` 최초 도입 (v11에서 일부 폐기)

---

## 1. 프로젝트 개요

### 1.1 프로젝트 목적
정보보안 컴플라이언스 증빙자료 수집 자동화 및 취약점 관리를 통합한 보안 운영 플랫폼 구축

### 1.2 주요 해결 과제

**증빙 수집 영역:**
- 흩어져 있는 자동화 스크립트 통합 관리
- 수동 실행의 번거로움 해소
- 실패 시 원인 파악의 어려움 해결
- 증빙 파일 정리/보관의 체계화
- 증빙 파일 버전(이력) 관리
- **증빙별 담당자 지정 및 다수 팀 협업 지원** (인사팀·법무팀 등이 각자 담당 증빙 업로드)
- **증빙 승인/반려 워크플로우** (관리자 검토 필수, 반려 시 사유 필수)
- **감사 주기별 Framework 관리** (전년도 구조를 상속하여 신규 감사 준비)
- 통제 항목 중심의 단일 작업 흐름 (통제→증빙유형→수집방식을 한 자리에서)

**취약점 관리 영역:**
- 점검 결과 엑셀 Import 및 통합 관리
- 담당자 자체 배정 및 조치 일정 결재 프로세스
- 조치 현황 추적 및 리마인더 자동 발송

### 1.3 대상 사용자

| 역할 | 설명 | 접근 권한 |
|------|------|----------|
| 관리자 (admin) | 보안팀 관리자 | 전체 기능 (증빙 수집 + 취약점 관리) |
| 담당자 (approver/developer + permission_evidence) | 인사팀·법무팀 등 각 팀의 증빙 업로드 담당자 | 본인 담당 증빙의 제출만 (읽기/업로드) |
| 결재자 (approver) | 개발팀 팀장 | 취약점 관리 (일정 결재) |
| 개발자 (developer) | 조치 담당자 | 취약점 관리 (조치 입력) |

**중요 원칙: 역할 ENUM은 변경하지 않고 `users.permission_evidence` 플래그와 `evidence_types.owner_user_id` 조합으로 세분 제어함.**

---

## 2. 시스템 구조

### 2.1 메뉴 구조

```
SecuHub
│
├── 📊 대시보드
│
├── 📁 증빙 수집
│   ├── 📋 통제 항목
│   ├── ▶️ 수집 작업
│   └── 📁 증빙 파일
│
├── 🐛 취약점 관리
│   └── 📋 취약점 목록
│
└── ⚙️ 시스템
    ├── 👥 계정 관리
    └── ⚙️ 설정
```

### 2.2 역할별 화면 구성

**관리자 (보안팀)**
```
[대시보드]
[증빙 수집]
  - 통제 항목 (증빙 상세 + 이력 보기)
  - 수집 작업
  - 증빙 파일 (전체 이력 관리)
[취약점 관리]
  - 취약점 목록
[시스템]
  - 계정 관리
  - 설정
```

**개발자/결재자**
```
[전체 현황] - 전체 취약점 현황 대시보드
[나의 현황] - 본인 배정 취약점
[취약점 목록] - 전체 목록 + 담당자 지정
[결재 관리] - 결재 승인/반려 (결재자용)
[조치 이력] - 완료 이력
```

---
## 3. 증빙 수집 기능

### 3.0 설계 원칙 (v11 재편)

증빙 수집은 **감사 주기별 Framework → 통제 항목 → 증빙 유형 → 파일**의 계층 구조 위에서, **관리자와 담당자의 협업 워크플로우**를 지원합니다.

#### 핵심 개념 4가지

1. **Framework = 감사 주기 스냅샷.** ISMS-P 2026, ISMS-P 2025 같이 감사 연도별로 독립된 Framework를 생성합니다. 각 Framework는 통제 항목/증빙 유형/수집 작업의 독립된 복제본을 가집니다.

2. **Framework 상속.** 새 감사 주기를 시작할 때 전년도 Framework를 **원클릭 상속**하여 구조를 복제합니다(파일/실행이력은 제외). 이전에 반영했던 변경사항이 자동 반영됩니다.

3. **담당자(owner_user_id)와 승인자(admin)의 역할 분리.** 각 증빙 유형은 담당자가 지정되며, 담당자가 업로드하면 관리자가 검토 후 승인/반려합니다. 반려 시 사유가 필수입니다.

4. **이메일 중심 알림.** 업로드·반려·마감 등 이벤트 시 담당자/관리자에게 이메일 발송. 인앱 알림 벨은 두지 않고, "내 할 일" 대시보드가 그 역할을 수행합니다.

#### 페이지 역할 분리

| 페이지 | 역할 | 주요 작업 |
|--------|------|-----------|
| **통제 항목** `/controls` | **증빙 제출 허브** — 수집의 메인 작업 화면 | Framework 목록 진입 → 상세 → 통제 항목 인라인 확장 → 증빙 유형 카드에서 검토·업로드·자동수집 설정 |
| **수집 작업** `/jobs` | **자동 수집 운영 대시보드** — 전체 스케줄 모니터링 | 실행 현황, 실패 작업 추적, 작업 등록(양방향 가능) |
| **증빙 파일** `/files` | **감사 대응 이력 조회** — 읽기 중심 | 기간별 조회, 감사용 일괄 다운로드, 커버리지 리포트 |
| **내 할 일** `/my-tasks` (신규) | **담당자 전용 워크스페이스** | 반려/마감임박/미제출/검토중/완료 섹션별 조회 및 업로드 |

---

### 3.1 통제 항목 — 증빙 제출 허브

통제 항목 메뉴 진입 시 **2단계 흐름**으로 동작합니다.

#### 3.1.1 1단계: Framework 목록 (진입 페이지)

사이드바 "통제 항목" 클릭 시 첫 화면.

**구성 요소:**
- **최근 작업 중 카드** (상단 다크 카드)
  - 가장 최근 접근한 Framework. 이름, 진척률(%), 수집 완료 수, 검토 대기 배지, 마지막 접속 시각
  - 클릭 시 해당 Framework 상세로 이동 (일상 작업은 대부분 여기서 시작)
- **전체 Framework 테이블**
  - 컬럼: Framework명(상속 출처 포함), 통제 수, 증빙 수, 작업 수, 상태(진행중/완료/아카이브)
  - Framework명에 검토 대기 배지 (예: `ISMS-P 2026 [● 검토 2]`)
  - 행 클릭 → 해당 Framework 상세 진입
- **우측 상단 액션**
  - [상속하여 생성] — 기존 Framework 상속 모달
  - [+ 새 Framework] — 빈 Framework 생성

#### 3.1.2 2단계: Framework 상세 (기존 관리자 UI 유지)

Framework 목록에서 특정 Framework 클릭 시 진입. **기존 스크린샷 레이아웃을 그대로 유지**합니다.

**상단 헤더:**
- Framework 이름 자체가 **드롭다운 트리거**. 클릭 시 메뉴:
  - 다른 Framework로 즉시 전환
  - 전체 Framework 목록으로 복귀
  - 기존 Framework 상속하여 새로 만들기
- Framework명 옆 검토 대기 배지
- 우측 상단: [엑셀 Import] [+ 통제항목 추가]

**필터 바:**
- 코드/항목명 검색
- 상태 필터 탭: 전체 / 완료 / 진행중 / 미수집 / **검토 대기** (신규)
- 우측 통계 카운트 (전체 N · 완료 N · 진행중 N · 미수집 N)

**통제 항목 테이블:**

| 컬럼 | 설명 |
|------|------|
| ▾ | 확장 토글 |
| 코드 | 1.1.1 형식 |
| 영역 | 예: 관리체계 수립 |
| 항목명 | 증빙 수집이 필요한 통제 |
| 수집현황 | `2/3` + 진척 바 |
| 상태 | 완료/진행중/미수집/**● 검토 대기** |
| 담당자 | 해당 통제의 증빙 담당자(대표) |
| 관리 | ✏️ 🗑️ 액션 |

검토 대기 건이 있는 행은 **파란색 배경 강조** + 상태 배지 파란색.

**통제 항목 행 클릭 → 인라인 확장 (기존 패턴 유지):**

확장 영역에 "수집된 증빙 파일 N/M · 검토 대기 N건" 헤더 + [전체 다운로드 (ZIP)] [+ 증빙 유형] 버튼. 그 아래 **증빙 유형 카드 리스트**.

#### 3.1.3 증빙 유형 카드 — 협업 액션이 통합된 단위

각 증빙 유형 카드는 아래 구성:

```
📄 [증빙 유형명] [상태 뱃지]  v3 · 2.1MB · 담당자명 · 시간
                           [검토] [이력 (N) ▾] ⬇️ ⬆️ ⚙️ 🗑️
```

**액션 버튼 구성:**
- **[검토]** — 상태가 "검토 대기"일 때만 노출. 클릭 시 카드 내부로 **검토 패널 인라인 확장**:
  - 파일명·크기 + 미리보기/다운로드
  - 제출자 메모
  - [반려하기] [승인] 버튼
  - 반려 클릭 시 → 사유 입력 텍스트에어리어 노출 (필수). [반려 확정 · 이메일 발송]
- **[이력 (N) ▾]** — 과거 버전 펼침. 각 버전에 상태(승인/반려), 업로드 일자, 반려 사유(있을 경우)
- **⬇️ 다운로드** — 현재 버전
- **⬆️ 업로드** — 관리자 수동 업로드 (관리자 업로드는 자동 승인)
- **⚙️ 자동수집 설정** — 클릭 시 **자동 수집 패널 인라인 확장**:
  - 현재 연결된 수집 작업 리스트
  - [작업 추가] — 바로 등록
  - "수집 작업 페이지에서도 관리 가능 (양방향 동기화)" 링크
- **🗑️ 삭제**

#### 3.1.4 증빙 상태 전이

```
미수집 ──[담당자 업로드]──→ 검토 대기 ──[admin 승인]──→ 완료
                                ↓
                          [admin 반려 + 사유]
                                ↓
                             반려됨
                                ↓
                          [담당자 재업로드]
                                ↓
                            검토 대기
```

**중요:**
- 반려 시 **사유 입력 필수** (감사원이 나중에 검증 가능해야 함)
- 관리자 직접 업로드 시 자동 승인 (담당자 우회 업로드)

---

### 3.2 Framework 상속 기능

#### 3.2.1 사용 시나리오

새 감사 주기를 시작할 때(예: 2026년 감사 → 2027년 준비) 전년도 Framework 구조를 그대로 가져와서 파일만 새로 채우고 싶은 요구.

#### 3.2.2 동작 방식

**진입 경로 2가지:**
- Framework 목록 페이지 우측 상단 [상속하여 생성] 버튼
- Framework 상세의 드롭다운 메뉴 "기존 Framework 상속하여 새로 만들기"

**모달 구성:**
- 상속받을 Framework 선택 (드롭다운)
- 새 Framework 이름 입력 (예: "ISMS-P 2027")
- 복제 대상 확인:
  - ✅ 통제 항목 전체
  - ✅ 증빙 유형 전체 (담당자 포함)
  - ✅ 수집 작업 전체 (새 증빙 유형 ID로 재연결)
  - ❌ 파일 / 실행 이력 (복제 제외 — 빈 상태로 시작)
- 안내: "생성 시점의 스냅샷으로 복제됩니다. 이후 원본/복제본 간 동기화되지 않고 독립 관리됩니다."

**백엔드 처리:**
- `frameworks.parent_framework_id`에 원본 FK 기록
- 통제 항목·증빙 유형·수집 작업 복제 시 새 ID 부여, 관계 재연결
- 트랜잭션 단위로 처리 (실패 시 전체 롤백)

---

### 3.3 담당자 "내 할 일" — 협업 워크스페이스

#### 3.3.1 접근 권한

- 사이드바 메뉴 "내 할 일"로 진입 (담당자 전용)
- `permission_evidence=true` 인 사용자에게 노출
- 본인이 `owner_user_id` 로 지정된 증빙 유형만 표시

#### 3.3.2 화면 구성

**상단: 상태별 요약 5칸 카드**
- 반려 (빨강) / 마감 임박 (주황) / 미제출 (회색) / 검토 중 (파랑) / 완료 (초록)
- 각 카드에 건수 표시

**섹션 1: 🔴 반려됨 · 즉시 재제출 필요**
- 카드 내부에 반려 사유 + 반려자 + 반려 시각 직접 노출
- 카드 클릭 → 재제출 페이지로 이동

**섹션 2: 🟡 마감 임박 (7일 이내)**
- D-N 배지 + 마감일

**섹션 3: ⚪ 미제출**
- Framework/통제 항목 경로 표시

**섹션 4: 🔵 검토 중 · 관리자 승인 대기**
- 제출일 + 검토자 정보

**섹션 5: ✅ 완료** (접힌 상태, 펼치기 가능)

#### 3.3.3 증빙 재제출 페이지 (반려 케이스)

- 상단에 **반려 사유를 빨간 박스로 크게** 표시
- 파일 업로드 영역 (드래그 앤 드롭)
- 제출 메모 (선택)
- 제출 이력 (이전 버전들)

---

### 3.4 승인 플로우 및 이메일 알림

#### 3.4.1 승인/반려 액션

**관리자 측:**
- Framework 상세 → 통제 항목 확장 → 증빙 유형 카드 → [검토] 버튼 인라인 확장
- 승인: 원클릭 (확인 다이얼로그 없음)
- 반려: 사유 입력 → [반려 확정 · 이메일 발송]

**접근 지점 3가지:**
- 통제 항목 페이지 내 직접 검토
- 대시보드 "내 승인 대기" 위젯 → 클릭 시 해당 증빙으로 점프 + 검토 패널 자동 펼침
- 이메일 딥링크 → 해당 증빙으로 직접 진입

#### 3.4.2 이메일 알림

**기술 스택:** JavaMailSender + 기업 내부 SMTP (폐쇄망)

**발송 트리거:**
| 트리거 | 수신자 | 내용 |
|--------|--------|------|
| 담당자 업로드 | 관리자 | "새 증빙 제출" + 딥링크 |
| 관리자 반려 | 담당자 | "반려 안내 + 사유" + 재제출 딥링크 |
| 관리자 승인 | 담당자 | "승인 완료" 안내 |
| 마감 임박 (D-7, D-3, D-1) | 담당자 | "마감 임박" + "내 할 일" 링크 |
| 담당자 신규 배정 | 담당자 | "새 담당 증빙" + 해당 증빙 링크 |

**딥링크 예시:**
- 담당자: `https://secuhub.company.com/my-tasks/e-{evidence_type_id}`
- 관리자: `https://secuhub.company.com/controls/{framework_id}/{control_id}#e-{evidence_type_id}`

#### 3.4.3 알림 설정

Settings 페이지에서 담당자별 토글:
- 마감 임박 알림 받기
- 승인 완료 알림 받기
- 일간 요약 이메일 받기

---

### 3.5 대시보드 위젯

관리자 대시보드에 추가:

**상단 4개 KPI 카드 중 하나:**
- "내 승인 대기 N건" (파란 테두리 강조) — 클릭 시 Framework 상세로 점프

**하단 섹션:**
- "승인 대기 목록" — 제출자/시각/경로/바로가기
- "Framework별 진척" — 진척률 바 + 검토 대기 건수

---

### 3.6 수집 작업 페이지 (기존 유지)

v10 스펙 그대로 유지. 전체 자동 수집 작업의 운영 대시보드 역할. 단, 증빙 유형에서 ⚙️ 설정으로 등록한 작업은 여기서도 보이고, 여기서 등록한 작업은 해당 증빙 유형 카드에서도 보임 (양방향 동기화).

---

### 3.7 증빙 파일 페이지 (기존 유지)

v10 스펙 그대로 유지. 감사 대응 용도의 읽기 중심 조회.  
**주의: v10에서 언급된 "만료 임박 증빙 수" 위젯은 v11에서 제거** (만료 개념 폐기).


## 4. 취약점 관리 기능

### 4.1 취약점 목록

점검 회차 구분 없이 전체 취약점을 통합 관리합니다.

**컬럼 구성:**
| 컬럼 | 설명 |
|------|------|
| 점검 분류 | 웹 취약점, 데이터 보안, 인프라 |
| 장비 분류 | 웹서버, 방화벽, DB서버 등 |
| 호스트명 | 대상 호스트/서버명 |
| 점검 항목 코드 | WEB-001, INFRA-005 등 |
| 문제점 | 발견된 문제 설명 |
| 내용 | 상세 내용 |
| 담당자 | 조치 담당자 |
| 결재자 | 결재 담당자 |
| 계획일 | 조치 예정일 |
| 상태 | 미배정/결재대기/조치중/완료 |
| 비고 | 추가 메모 |

**엑셀 Import/Export:**
- [엑셀 내려받기] - 현재 목록 다운로드
- [엑셀 Import] - 점검 결과 업로드

### 4.2 취약점 상태 흐름 (4단계)

```
미배정(unassigned)
  │
  │ 담당자 + 계획일 + 결재자 입력 후 결재 요청
  ▼
결재대기(pending_approval)
  │                     │
  │ 승인                │ 반려 → 미배정(unassigned)으로 초기화
  ▼                           (담당자/계획일/결재자 리셋)
조치중(in_progress)
  │
  │ 조치 완료
  ▼
완료(done)
```

### 4.3 취약점 상세 (개발자용)

**담당자 지정 + 일정 등록 + 결재 요청 (한번에 처리):**
- 담당자 선택 (본인 또는 같은 팀원)
- 조치 계획일 입력
- 결재자 (팀장) 선택
- 결재 요청 버튼

**조치 결과:**
- 조치 내용 입력
- 조치 결과 제출

### 4.4 결재 관리 (결재자용)

**결재 대기 목록:**
- 요청자, 요청일
- 결재분류, 결재내용
- 승인 / 반려 버튼

**처리 완료 목록:**
- 승인/반려 이력

### 4.5 조치 이력

취약점에 대한 모든 행위를 자유 입력 형태로 기록합니다.
- 분류: VARCHAR 자유 입력 (예: "담당자 배정", "결재 요청", "조치 완료")
- 내용: TEXT 자유 입력

---

## 5. 계정 관리

### 5.1 계정 목록

| 컬럼 | 설명 |
|------|------|
| 사용자 | 이름, 이메일 |
| 소속 | 팀 정보 |
| 역할 | 관리자/결재자/개발자 |
| 접근 권한 | 증빙 수집 / 취약점 관리 |
| 마지막 로그인 | 최근 접속 시점 |
| 상태 | 활성/비활성 |

### 5.2 역할 정의

| 역할 | 설명 | 권한 |
|------|------|------|
| 관리자 (admin) | 보안팀 관리자 | 전체 기능 접근 |
| 결재자 (approver) | 개발팀 팀장 | 취약점 관리 + 일정 결재 |
| 개발자 (developer) | 조치 담당자 | 취약점 관리 (조치 입력) |

### 5.3 접근 권한

| 권한 | 설명 |
|------|------|
| 증빙 수집 | 통제 항목, 수집 작업, 증빙 파일 접근 |
| 취약점 관리 | 취약점 목록, 조치 입력 |

---

## 6. 데이터베이스 스키마

> 10개 테이블, 5개 ENUM, 16개 FK 관계
>
> **v11 변경 요약:**
> - ✅ `frameworks.parent_framework_id` (self-FK) 추가 — Framework 상속 근거
> - ✅ `evidence_files.review_status` (ENUM) 추가 — 승인/반려 상태
> - ✅ `evidence_files.reviewed_by` (FK → users) 추가
> - ✅ `evidence_files.review_note` (TEXT) 추가 — 반려 사유 (필수 when rejected)
> - ✅ `evidence_files.reviewed_at` (DATETIME) 추가
> - ✅ `evidence_files.submit_note` (TEXT) 추가 — 담당자 제출 메모
> - ✅ `notification_preferences` 신규 테이블 — 사용자별 이메일 알림 설정
> - ❌ `evidence_types.renewal_cycle_days` **제거** (v10에서 도입, v11에서 폐기)
> - ❌ `evidence_files.valid_until` **제거** (v10에서 도입, v11에서 폐기)
> - 🔧 `evidence_types.owner_user_id` 유지 (v10 도입분)

### 6.1 계정 관련

```sql
-- 사용자 계정
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    team VARCHAR(100),
    role ENUM('admin', 'approver', 'developer') NOT NULL DEFAULT 'developer',
    permission_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    permission_vuln BOOLEAN NOT NULL DEFAULT TRUE,
    status ENUM('active', 'inactive') NOT NULL DEFAULT 'active',
    last_login_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email),
    INDEX idx_users_role (role),
    INDEX idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 6.2 증빙 수집 관련

```sql
-- 프레임워크 (v11: 상속 지원)
CREATE TABLE frameworks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    parent_framework_id BIGINT NULL COMMENT 'v11: 상속 원본 Framework. NULL이면 신규 생성.',
    status ENUM('active', 'archived') NOT NULL DEFAULT 'active' COMMENT 'v11: 아카이브 처리용',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_frameworks_parent (parent_framework_id),
    INDEX idx_frameworks_status (status),
    FOREIGN KEY (parent_framework_id) REFERENCES frameworks(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 통제 항목
CREATE TABLE controls (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    framework_id BIGINT NOT NULL,
    code VARCHAR(50) NOT NULL,
    domain VARCHAR(200),
    name VARCHAR(500) NOT NULL,
    description TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_controls_code (code),
    FOREIGN KEY (framework_id) REFERENCES frameworks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 증빙 유형 (필요 증빙 체크리스트)
CREATE TABLE evidence_types (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    control_id BIGINT NOT NULL,
    name VARCHAR(300) NOT NULL,
    description TEXT,
    owner_user_id BIGINT NULL COMMENT 'v10: 증빙 담당자',
    due_date DATE NULL COMMENT 'v11: 이 Framework 내 이 증빙의 제출 마감일',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_evidence_types_owner (owner_user_id),
    INDEX idx_evidence_types_due (due_date),
    FOREIGN KEY (control_id) REFERENCES controls(id) ON DELETE CASCADE,
    FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 증빙 파일 (버전 관리 + v11 승인 플로우)
CREATE TABLE evidence_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_type_id BIGINT NOT NULL,
    execution_id BIGINT,
    file_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    collection_method ENUM('auto', 'manual') NOT NULL DEFAULT 'manual',
    uploaded_by BIGINT NULL COMMENT 'v11: 업로드 주체 (담당자 or 관리자)',
    submit_note TEXT NULL COMMENT 'v11: 담당자 제출 메모',
    review_status ENUM('pending', 'approved', 'rejected', 'auto_approved') NOT NULL DEFAULT 'pending' COMMENT 'v11: 관리자 수동 업로드는 auto_approved',
    reviewed_by BIGINT NULL COMMENT 'v11: 승인/반려한 관리자',
    review_note TEXT NULL COMMENT 'v11: 반려 사유 (반려 시 필수)',
    reviewed_at DATETIME NULL COMMENT 'v11: 승인/반려 시각',
    collected_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_evidence_files_review_status (review_status),
    INDEX idx_evidence_files_reviewed_by (reviewed_by),
    FOREIGN KEY (evidence_type_id) REFERENCES evidence_types(id) ON DELETE CASCADE,
    FOREIGN KEY (execution_id) REFERENCES job_executions(id) ON DELETE SET NULL,
    FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- v11 신규: 알림 설정
CREATE TABLE notification_preferences (
    user_id BIGINT PRIMARY KEY,
    email_on_rejection BOOLEAN NOT NULL DEFAULT TRUE,
    email_on_approval BOOLEAN NOT NULL DEFAULT TRUE,
    email_on_new_assignment BOOLEAN NOT NULL DEFAULT TRUE,
    email_on_due_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    email_daily_digest BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 수집 작업
CREATE TABLE collection_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    description TEXT,
    job_type ENUM('web_scraping', 'excel_extract', 'log_extract') NOT NULL,
    script_path VARCHAR(1000),
    evidence_type_id BIGINT,
    schedule_cron VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (evidence_type_id) REFERENCES evidence_types(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 실행 기록
CREATE TABLE job_executions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    status ENUM('running', 'success', 'failed') NOT NULL DEFAULT 'running',
    started_at DATETIME,
    finished_at DATETIME,
    error_message TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (job_id) REFERENCES collection_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 6.3 취약점 관리 관련

```sql
-- 취약점
CREATE TABLE vulnerabilities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category VARCHAR(200),
    device_type VARCHAR(200),
    hostname VARCHAR(300),
    check_code VARCHAR(100),
    problem TEXT,
    content TEXT,
    assignee_id BIGINT,
    approver_id BIGINT,
    plan_date DATE,
    status ENUM('unassigned', 'pending_approval', 'in_progress', 'done') NOT NULL DEFAULT 'unassigned',
    note TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vuln_status (status),
    INDEX idx_vuln_assignee (assignee_id),
    FOREIGN KEY (assignee_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (approver_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 조치 이력
CREATE TABLE vuln_action_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vulnerability_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(200),
    content TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vulnerability_id) REFERENCES vulnerabilities(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 결재 요청
CREATE TABLE approval_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vulnerability_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    approver_id BIGINT NOT NULL,
    category VARCHAR(200),
    content TEXT,
    status ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (vulnerability_id) REFERENCES vulnerabilities(id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (approver_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

---

## 7. 기술 스택

### 7.1 Frontend

| 기술 | 선정 이유 |
|------|----------|
| Vue 3 | 사용자 친숙도, 컴포지션 API |
| TypeScript | 타입 안정성 |
| Vite | 빠른 개발 환경 |
| Pinia | Vue 3 공식 상태관리 |
| PrimeVue | 풍부한 UI 컴포넌트 |
| TailwindCSS | 유틸리티 기반 스타일링 |

### 7.2 Backend

| 기술 | 선정 이유 |
|------|----------|
| Java 21 (LTS) | 최신 LTS, virtual thread 등 최신 기능 활용 |
| Spring Boot 3.4.1 | 빠른 개발, 자동 설정, 풍부한 생태계 |
| Spring Security + JWT | 인증/인가 처리 |
| Spring Data JPA (Hibernate) | ORM, MariaDB 연동 |
| MariaDB | 안정적인 RDBMS, MySQL 호환 |
| H2 Database | 테스트용 인메모리 DB (MariaDB 호환 모드) |
| Spring Scheduler | 스케줄 기반 수집 작업 실행 (@Scheduled) |
| Apache POI | 엑셀 Import/Export 처리 |
| JPA ddl-auto | 엔티티 기반 테이블 자동 생성 |

### 7.3 시스템 구성

외부망(개발 PC)에서 빌드한 산출물을 폐쇄망 서버에 배포하는 구조입니다.

```
[외부망 - 개발 PC]
  ./gradlew bootJar  → secuhub.jar (Backend)
  npm run build      → dist/       (Frontend 정적 파일)

      ↓ JAR + dist/ 를 폐쇄망으로 이관

[폐쇄망 - 운영 서버]
┌─────────────────────────────────────────────┐
│                   Nginx                     │
│     정적 파일 서빙 (dist/) + 리버스 프록시      │
│                  Port: 80                   │
└─────────────┬───────────────┬───────────────┘
              │               │
     /        │      /api/*   │
              │               │
    ┌─────────▼─────┐  ┌─────▼──────────┐
    │  Vue 3 SPA    │  │  Spring Boot   │
    │ (dist/ 정적)   │  │  secuhub.jar   │
    │               │  │  Port: 8080    │
    └───────────────┘  │                │
                       │  ┌───────────┐ │
                       │  │ Scheduler │ │
                       │  │ (내장)     │ │
                       │  └───────────┘ │
                       └───────┬────────┘
                               │
                       ┌───────▼────────┐
                       │   MariaDB      │
                       │  Port: 3306    │
                       └────────────────┘
```

---

## 8. 프로젝트 구조

### 8.1 Backend (Spring Boot)

```
secuhub-backend/
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/secuhub/
│       │   │   ├── SecuhubApplication.java
│       │   │   ├── config/
│       │   │   │   ├── SecurityConfig.java
│       │   │   │   ├── SpaWebMvcConfig.java
│       │   │   │   ├── WebMvcConfig.java
│       │   │   │   ├── JpaConfig.java
│       │   │   │   ├── AsyncConfig.java
│       │   │   │   ├── SchedulerConfig.java
│       │   │   │   ├── DataInitializer.java
│       │   │   │   └── EvidenceDataInitializer.java
│       │   │   ├── config/jwt/
│       │   │   │   ├── JwtTokenProvider.java
│       │   │   │   ├── JwtAuthenticationFilter.java
│       │   │   │   ├── JwtAuthenticationEntryPoint.java
│       │   │   │   ├── JwtAccessDeniedHandler.java
│       │   │   │   └── UserPrincipal.java
│       │   │   ├── common/
│       │   │   │   ├── BaseEntity.java
│       │   │   │   ├── dto/
│       │   │   │   │   ├── ApiResponse.java
│       │   │   │   │   └── PageResponse.java
│       │   │   │   └── exception/
│       │   │   │       ├── BusinessException.java
│       │   │   │       ├── ResourceNotFoundException.java
│       │   │   │       └── GlobalExceptionHandler.java
│       │   │   └── domain/
│       │   │       ├── user/
│       │   │       │   ├── entity/      (User, UserRole, UserStatus)
│       │   │       │   ├── repository/  (UserRepository)
│       │   │       │   ├── dto/         (LoginRequest, LoginResponse)
│       │   │       │   ├── service/     (AuthService)
│       │   │       │   └── controller/  (AuthController)
│       │   │       ├── evidence/
│       │   │       │   ├── entity/     (Framework, Control, EvidenceType,
│       │   │       │   │               EvidenceFile, CollectionJob, JobExecution,
│       │   │       │   │               CollectionMethod, JobType, ExecutionStatus)
│       │   │       │   ├── repository/ (6개 Repository)
│       │   │       │   ├── dto/        (FrameworkDto, ControlDto, EvidenceFileDto,
│       │   │       │   │               CollectionJobDto, ExcelImportDto)
│       │   │       │   ├── service/    (FrameworkService, ControlService,
│       │   │       │   │               EvidenceFileService, CollectionJobService,
│       │   │       │   │               ExcelImportService, SchedulerService,
│       │   │       │   │               ScriptExecutionService)
│       │   │       │   └── controller/ (FrameworkController, ControlController,
│       │   │       │                    EvidenceFileController, CollectionJobController)
│       │   │       └── vulnerability/
│       │   │           ├── entity/     (Vulnerability, VulnActionLog,
│       │   │           │               ApprovalRequest, VulnStatus, ApprovalStatus)
│       │   │           └── repository/ (3개 Repository)
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       └── application-prod.yml
│       └── test/
│           ├── java/com/secuhub/
│           │   ├── SchemaValidationTest.java
│           │   ├── AuthenticationTest.java
│           │   └── ScriptExecutionTest.java
│           └── resources/
│               └── application-test.yml
├── gradle/wrapper/
├── settings.gradle
├── gradlew
└── gradlew.bat
```

### 8.2 Frontend (Vue 3)

```
secuhub-frontend/
├── src/
│   ├── components/
│   │   └── layout/
│   ├── views/
│   │   ├── admin/
│   │   │   ├── DashboardView.vue
│   │   │   ├── ControlsView.vue
│   │   │   ├── JobsView.vue
│   │   │   ├── FilesView.vue
│   │   │   ├── AccountsView.vue
│   │   │   └── PlaceholderView.vue
│   │   ├── dev/
│   │   └── auth/
│   ├── router/
│   ├── stores/
│   ├── services/
│   │   ├── api.ts
│   │   └── evidenceApi.ts
│   ├── types/
│   │   ├── index.ts
│   │   └── evidence.ts
│   └── assets/
├── package.json
├── vite.config.ts
├── tsconfig.json
└── tailwind.config.js
```

---

## 9. 환경 설정

### 9.1 프로필 전략

| 프로필 | DB | 용도 | ddl-auto | 활성화 방법 |
|--------|-----|------|----------|------------|
| dev | MariaDB (localhost) | 로컬 개발 | create | 기본값 (별도 설정 불필요) |
| test | H2 인메모리 | 자동화 테스트 | create | `@ActiveProfiles("test")` |
| prod | MariaDB (환경변수) | 운영 서버 | validate | `SPRING_PROFILES_ACTIVE=prod` |

```yaml
# application.yml (공통)
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

**설정 파일 구조:**
```
src/main/resources/
├── application.yml          # 공통 설정 + 기본 프로필 dev
├── application-dev.yml      # 로컬 MariaDB
├── application-prod.yml     # 운영 MariaDB (환경변수 주입)

src/test/resources/
├── application-test.yml     # H2 인메모리 (테스트 전용)
```

**의존성 구분:**
```groovy
runtimeOnly 'org.mariadb.jdbc:mariadb-java-client'   // dev, prod
testRuntimeOnly 'com.h2database:h2'                    // test만
```

### 9.2 빌드 및 배포

**외부망 (개발 PC) — 빌드**

```bash
# Backend JAR 빌드
cd secuhub-backend
./gradlew bootJar
# 결과: app/build/libs/secuhub.jar

# Frontend 빌드
cd secuhub-frontend
npm install
npm run build
# 결과: dist/
```

**폐쇄망으로 이관할 파일:**
- `secuhub.jar`
- `dist/` (프론트엔드 빌드 산출물)

**폐쇄망 (운영 서버) — 실행**

```bash
# 1. MariaDB 데이터베이스 생성 (최초 1회)
mysql -u root -p
> CREATE DATABASE secuhub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
> CREATE USER 'secuhub'@'localhost' IDENTIFIED BY 'secuhub_password';
> GRANT ALL PRIVILEGES ON secuhub.* TO 'secuhub'@'localhost';
> FLUSH PRIVILEGES;

# 2. 환경변수 설정
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:mariadb://localhost:3306/secuhub?serverTimezone=Asia/Seoul
export DB_USERNAME=secuhub
export DB_PASSWORD=secuhub_password
export JWT_SECRET=production-secret-key-at-least-256-bits

# 3. Backend 실행
java -jar secuhub.jar

# 4. Nginx 설정 후 dist/ 서빙
```

**개발 환경 (외부망 로컬)**

```bash
# Backend 개발 서버 (기본 프로필 dev 자동 적용)
cd secuhub-backend
./gradlew bootRun

# Frontend 개발 서버
cd secuhub-frontend
npm run dev

# 테스트 실행
./gradlew clean test
```

### 9.3 데모 계정

dev 프로필 기동 시 `DataInitializer`가 자동 생성합니다.

| 역할 | 이메일 | 비밀번호 | 팀 |
|------|--------|----------|-----|
| 관리자 | admin@company.com | admin1234 | 보안팀 |
| 결재자 | park_tl@company.com | park1234 | 백엔드팀 |
| 개발자 | kim@company.com | dev1234 | 백엔드팀 |
| 개발자 | lee@company.com | dev1234 | 프론트엔드팀 |
| 개발자 | choi@company.com | dev1234 | 백엔드팀 |

---

## 10. 화면 목록

### 10.1 관리자 화면

| 화면 | 경로 | 설명 |
|------|------|------|
| 대시보드 | /dashboard | 전체 현황 요약 + **"내 승인 대기" 위젯** + 승인 대기 목록 (v11) |
| 통제 항목 | /controls | **Framework 목록** 진입 페이지 (v11) |
| Framework 상세 | /controls/:frameworkId | 통제 항목 테이블 + 인라인 확장 + 증빙 유형 카드 검토 UI (v11) |
| 수집 작업 | /jobs | **자동 수집 운영 대시보드** — 전체 작업 실행 현황, 실패 추적 |
| 작업 상세 | /jobs/:id | 실행 결과, 실패 상세, 연결 증빙 점프 링크 |
| 증빙 파일 | /files | **감사 대응 이력 조회** — 읽기 전용, 기간별 일괄 다운로드 |
| 취약점 목록 | /vulns | 전체 취약점 목록 |
| 계정 관리 | /accounts | 사용자 계정 관리 |
| 설정 | /settings | 이메일 알림 설정 (v11) |

### 10.2 담당자 화면 (v11 신규)

| 화면 | 경로 | 설명 |
|------|------|------|
| 내 할 일 | /my-tasks | 담당자 전용 워크스페이스. 반려/마감임박/미제출/검토중/완료 섹션 |
| 증빙 재제출 | /my-tasks/:evidenceTypeId | 반려 사유 + 재업로드 |

### 10.3 개발자/결재자 화면

| 화면 | 경로 | 설명 |
|------|------|------|
| 전체 현황 | /dev/dashboard | 전체 취약점 현황 |
| 나의 현황 | /dev/my-vulns | 배정된 취약점 |
| 취약점 목록 | /dev/vulns | 전체 목록, 담당자 지정 |
| 취약점 상세 | /dev/vulns/:id | 담당자 지정, 일정 등록, 조치 입력 |
| 결재 관리 | /dev/approvals | 결재 승인/반려 (결재자용) |
| 조치 이력 | /dev/history | 완료 이력 |

---

## 11. 개발 로드맵

### Phase 1: 기반 구조 ✅
- [x] Spring Boot 프로젝트 초기 설정 (Gradle)
- [x] JPA 엔티티 정의 (9개 테이블, 10개 Repository)
- [x] 프로필 전략 (dev/test/prod)
- [x] API 기본 구조 (CORS, 예외 처리, 공통 응답)
- [x] Vue 3 프론트엔드 기본 레이아웃
- [x] 스키마 검증 테스트 (7개 통과)
- [x] Spring Security + JWT 인증 시스템
- [x] 프론트엔드 JWT 인증 연동 (Mock → 실제 백엔드)
- [x] SPA 정적 리소스 서빙 및 Vue Router 포워딩
- [x] JWT 인증 통합 테스트 (11개 통과)

### Phase 2: 증빙 수집 ✅
- [x] 프레임워크/통제항목 CRUD API
- [x] 통제 항목 엑셀 Import (Apache POI)
- [x] 통제 항목 - 증빙 상세 + 이력 보기
- [x] 수집 작업 등록/실행
- [x] Spring Scheduler 연동 (Cron 기반 자동 실행)
- [x] 증빙 파일 버전 관리
- [x] 증빙 파일 전체 이력 페이지
- [x] 수집 작업 실제 스크립트 실행 (ProcessBuilder 연동, Windows/Linux 크로스 플랫폼)
- [x] 증빙 파일 다운로드 API (Blob 다운로드, RFC 5987 한글 파일명)
- [x] ScriptExecutionTest 8개 통과 (전체 26개)
- [x] ControlsView 기능 강화 — ZIP 다운로드, 이력 토글, CRUD 완성, 검색/필터
  - [x] 전체 다운로드 (ZIP): EvidenceFileService.downloadZip() + Controller 엔드포인트, ZipOutputStream 스트리밍
  - [x] 버전 이력 토글: 최신 파일만 기본 표시, [이력 (N)] 클릭 시 전체 펼침/접기
  - [x] 증빙 유형 삭제: UI 삭제 버튼 추가 (confirm 확인 포함)
  - [x] 파일 삭제: 이력 확장 시 파일별 개별 삭제 버튼
  - [x] 통제항목 수정: 수정 다이얼로그 (코드/영역/항목명/설명)
  - [x] 검색/필터: 텍스트 검색 (코드·항목명·영역) + 상태 탭 필터 (전체/완료/진행중/미수집)
  - [x] UX: 토스트 알림, 수집현황 미니 프로그레스바, 슬라이드 애니메이션

### Phase 3: 취약점 관리
- [ ] 취약점 엑셀 Import (Apache POI)
- [ ] 취약점 목록 (보안팀용)
- [ ] 담당자 배정 + 일정 등록 + 결재 요청 (통합)
- [ ] 결재 승인/반려 프로세스
- [ ] 조치 이력 기록
- [ ] 개발자용 화면

### Phase 4: 시스템 관리
- [ ] 계정 관리 (CRUD)
- [ ] 역할/권한 관리
- [ ] 알림 설정 (Slack Webhook, 이메일 - JavaMailSender)
- [ ] 대시보드 통계
- [ ] 감사 로그 (audit_logs 테이블 + AOP 기반 행위 추적)
- [ ] 감사용 리포트 출력
- [ ] 보안 강화: HttpOnly 쿠키 인증 전환 (localStorage → Cookie)
- [ ] 보안 강화: CSRF 방어 재활성화
- [ ] 보안 강화: 파일 업로드 검증 (확장자, MIME, Path Traversal)
- [ ] 보안 강화: scriptPath Command Injection 방어
- [ ] 보안 강화: API 응답에서 filePath 제거
- [ ] 보안 강화: Rate Limiting (로그인 브루트포스 방지)
- [ ] 보안 강화: 보안 헤더 (HSTS, X-Content-Type-Options, X-Frame-Options)
- [ ] 보안 강화: prod 환경 Swagger 접근 차단

### Phase 5: 증빙 수집 협업 체계 구축 (v11)

**5-1. 스키마 마이그레이션**
- [ ] `frameworks.parent_framework_id` (self-FK) + `status` 컬럼 추가
- [ ] `evidence_types.owner_user_id` 유지 (v10 기존분), `due_date` 컬럼 추가
- [ ] `evidence_types.renewal_cycle_days` **제거** (기존 NULL이면 drop 안전)
- [ ] `evidence_files.valid_until` **제거**
- [ ] `evidence_files`에 승인 컬럼 일괄 추가: `review_status`, `reviewed_by`, `review_note`, `reviewed_at`, `uploaded_by`, `submit_note`
- [ ] `notification_preferences` 테이블 신규
- [ ] 기존 파일은 `review_status='auto_approved'` 로 일괄 설정 (하위 호환)
- [ ] 엔티티·DTO·Repository 반영

**5-2. 권한 체계 조정**
- [ ] `SecurityConfig` 수정: `/api/v1/evidence-types/{id}/files` POST (업로드) 는 `admin` + `permission_evidence=true AND owner_user_id=currentUser` 허용
- [ ] Service 레이어에 본인 담당 증빙 필터 추가 (`@PreAuthorize` 또는 수동 체크)
- [ ] `/api/v1/my-tasks` 담당자 전용 API 신규

**5-3. Framework 목록 진입 페이지**
- [ ] Vue: `/controls` 라우트를 "FrameworkListView" 로 전환
- [ ] 최근 작업 중 카드 + 전체 Framework 테이블
- [ ] 검토 대기 집계 API: `GET /api/v1/frameworks/pending-reviews`
- [ ] Framework 상세 라우트 신설: `/controls/:frameworkId` → 기존 ControlsView 로직 유지

**5-4. 승인 플로우 구현**
- [ ] 증빙 유형 카드에 `[검토]` 버튼 + 인라인 패널
- [ ] `POST /api/v1/evidence-files/{id}/approve`
- [ ] `POST /api/v1/evidence-files/{id}/reject` (body: review_note 필수 검증)
- [ ] 반려 시 상태 전이 및 담당자 "내 할 일" 반영

**5-5. 담당자 "내 할 일" 화면**
- [ ] 사이드바 메뉴 (permission_evidence=true 인 사용자에게 노출)
- [ ] `MyTasksView.vue` — 5개 섹션 구성
- [ ] 증빙 재제출 페이지
- [ ] `GET /api/v1/my-tasks` 통합 API (반려/마감임박/미제출/검토중/완료 섹션별)

**5-6. Framework 상속**
- [ ] 상속 모달 (Framework 목록 페이지 + Framework 상세 드롭다운)
- [ ] `POST /api/v1/frameworks/inherit` — 트랜잭션 단위 복제
  - [ ] 통제 항목 복제 → ID 매핑 테이블 유지
  - [ ] 증빙 유형 복제 (owner_user_id 포함) → ID 매핑
  - [ ] 수집 작업 복제 → 새 evidence_type_id 로 재연결
  - [ ] 파일·실행이력 복제 안 함
- [ ] 복제 실패 시 롤백 + 에러 메시지

**5-7. 이메일 알림 (JavaMailSender)**
- [ ] `application-prod.yml` SMTP 설정
- [ ] `EmailNotificationService` — 템플릿 기반 발송
- [ ] 트리거: 업로드/승인/반려/마감임박/신규배정
- [ ] 딥링크 생성 유틸
- [ ] `notification_preferences` 존중

**5-8. 대시보드 위젯**
- [ ] "내 승인 대기" KPI 카드 (파란 테두리)
- [ ] 승인 대기 목록 섹션
- [ ] Framework별 진척 + 검토 대기 카운트
- [ ] **만료 임박 증빙 위젯은 제거** (v10에서 있었다면)

**5-9. UI 정비 (배지 추가)**
- [ ] Framework 목록 테이블에 `[검토 N]` 배지
- [ ] 통제 항목 행에 검토 대기 강조 (파란 배경)
- [ ] 증빙 유형 카드 배지: `● 검토 대기` 상태 추가
- [ ] 상태 필터 탭에 "검토 대기" 추가

### Phase 6: 증빙 운영 고도화 (후순위 — 향후 고려)

v11에서도 보류한 항목들로, 실제 운영 피드백을 받은 뒤 도입 판단합니다.

- [ ] **증빙 ↔ 통제 N:N 매핑** — 같은 정책서를 여러 통제에 재사용 (Evidence Library 도입)
- [ ] **증빙 요청 워크플로우** — 보안팀이 타 부서에 공식 요청
- [ ] **감사원(auditor) 역할** — 외부 감사 시 임시 계정
- [ ] **증빙별 코멘트/검토 노트** — 버전별 맥락 기록
- [ ] **통제 준비도 점수** — 수집률 + 최신성 가중 복합 지표
- [ ] **증빙 유형 단위 마감일 리마인드 고도화** (주/일 단위 스케줄러)

---

## 12. 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| 4.0 | 2025-03-11 | 최초 작성 |
| 5.0 | 2025-03-25 | 스키마 리뷰 반영 — assessments 테이블 제거, evidence_types.file_type 제거, job_executions.log_file_path 제거, vulnerabilities 13컬럼 재설계, 취약점 상태 5단계→4단계, vuln_action_logs/approval_requests 간소화, 프로필 전략 정비(dev/test/prod), 개발 로드맵 Phase 1 진행 현황 갱신 |
| 6.0 | 2025-04-06 | Phase 1 완료 — Spring Security + JWT 인증 시스템 구현 (JwtTokenProvider, JwtAuthenticationFilter, AuthService, AuthController), 프론트엔드 JWT 연동 (Mock 제거, ApiResponse 래핑 대응, snake_case→camelCase 정합), SpaWebMvcConfig 추가 (Vue Router History 모드 지원), AuthenticationTest 11개 추가, 프로젝트 구조에 config/jwt/, domain/user/dto·service·controller 패키지 반영 |
| 7.0 | 2025-04-08 | Phase 2 완료 — 증빙 수집 전체 구현. Backend: evidence 도메인 DTO 5개 (FrameworkDto, ControlDto, EvidenceFileDto, CollectionJobDto, ExcelImportDto), Service 6개 (FrameworkService, ControlService, EvidenceFileService, CollectionJobService, ExcelImportService, SchedulerService), Controller 4개 (FrameworkController, ControlController, EvidenceFileController, CollectionJobController), SchedulerConfig (@EnableScheduling), EvidenceDataInitializer (dev 데모 데이터). Frontend: types/evidence.ts 타입 정의, services/evidenceApi.ts API 클라이언트, ControlsView.vue (통제항목 목록 + 행 확장 증빙 상세 + 엑셀 Import + 파일 업로드), JobsView.vue (수집 작업 목록 + 상세 패널 + 수동 실행), FilesView.vue (증빙 파일 전체 이력 리스트/타임라인 뷰 + 통계 카드), router/index.ts PlaceholderView→실제 화면 교체. 스크립트 실행(ProcessBuilder)과 파일 다운로드는 시뮬레이션 상태 (TODO) |
| 8.0 | 2025-04-09 | Phase 2 완전 완료 + 보안 로드맵 수립. **Backend**: ScriptExecutionService 신규 (ProcessBuilder 기반 스크립트 실행, Windows/Linux 크로스 플랫폼, @Async 비동기 실행, 타임아웃/경로 보안 검증, output 파일 자동 수집→EvidenceFile 등록), CollectionJobService 수정 (시뮬레이션→ScriptExecutionService 위임), SchedulerService 수정 (동기 실행 위임), EvidenceFileService 수정 (download 메서드 신규, saveFile 절대경로 변환, Content-Type 16종 추정), EvidenceFileDto에 DownloadResponse 추가, EvidenceFileController download API 완성 (RFC 5987 한글 파일명), application-dev.yml에 scripts 설정 추가. **Frontend**: evidenceApi.ts에 Blob 다운로드 함수 추가 (JWT 토큰 자동 포함), ControlsView/FilesView 다운로드 <a href>→Blob 방식 전환. **Test**: ScriptExecutionTest 8개 추가 (전체 26개 통과). **보안 검토**: 현재 시큐어 코딩 상태 분석 완료, Phase 4에 보안 강화 항목 추가 — HttpOnly 쿠키 전환, 감사 로그(audit_logs) 인프라, CSRF 재활성화, 파일 업로드 검증, Command Injection 방어, Rate Limiting, 보안 헤더, prod Swagger 차단. 프로젝트 구조에 ScriptExecutionService, ScriptExecutionTest 반영 |
| 9.0 | 2025-04-21 | ControlsView 기능 강화. **Backend**: EvidenceFileService에 downloadZip() 메서드 신규 (통제항목별 모든 증빙유형 최신 파일 ZipOutputStream 스트리밍, 빈 ZIP 시 README.txt, 파일 누락 시 skip+warn), EvidenceFileController에 GET /api/v1/evidence-files/zip/{controlId} 엔드포인트 신규 (HttpServletResponse 직접 스트리밍, RFC 5987 한글 파일명). **Frontend**: evidenceApi.ts에 downloadZip() 추가, ControlsView.vue 전면 개선 — ① 전체 다운로드(ZIP) 버튼, ② 버전 이력 토글 (최신만 기본 표시, [이력(N)] 클릭 시 펼침), ③ 증빙 유형 삭제 (confirm 포함), ④ 파일 삭제 (이력 확장 시 파일별), ⑤ 통제항목 수정 다이얼로그 (코드/영역/항목명/설명), ⑥ 검색/필터 (텍스트 검색 + 상태 탭 필터). UX 개선: 토스트 알림, 수집현황 미니 프로그레스바, 다이얼로그 배경클릭 닫기, 슬라이드 애니메이션 |
| 10.0 | 2026-04-21 | **증빙 수집 구조 재편 + 운영 요소 도입.** v9까지는 통제항목/수집작업/증빙파일 세 페이지가 독립 CRUD로 분리되어 있어, "특정 증빙의 수집 방식 설정" 같은 단순 작업도 다중 페이지 왕복이 필요했음. v10에서는 **통제 항목을 증빙 제출 허브로 승격**하고 나머지 두 페이지는 보조 역할(운영 모니터링·감사 조회)로 재정의. **주요 변경**: ① §3 증빙 수집 기능 전면 재작성 (페이지별 역할 재정의, 증빙 유형 상세 패널 도입 — 파일이력/수동업로드/자동수집 3탭 구조), ② 증빙 만료·갱신 개념 도입 (`evidence_types.renewal_cycle_days`, `evidence_files.valid_until`), ③ 증빙 담당자 개념 도입 (`evidence_types.owner_user_id` FK → users), ④ 통제 항목 상태에 "만료주의/만료됨" 추가 및 우선순위 판정 규칙, ⑤ 증빙 파일 페이지 읽기 전용화 (업로드/삭제는 통제항목 페이지로 일원화), ⑥ 수집 작업 페이지에 "연결 증빙" 컬럼 + 통제항목 점프 링크, ⑦ 대시보드 만료 임박 증빙 위젯. **스키마**: evidence_types 2컬럼 추가(owner_user_id, renewal_cycle_days), evidence_files 1컬럼 추가(valid_until), FK 관계 13→14개. **로드맵**: Phase 5 신설 (증빙 수집 재편 5-1~5-5), Phase 6 후순위 보류 항목 명시 (N:N 매핑, 증빙 요청 워크플로우, 감사원 역할, 코멘트, 준비도 점수) |
| 11.0 | 2026-04-22 | **협업 체계 도입 + UI 구조 확정.** v10의 허브화 철학은 유지하되, 실제 운영 요구사항 재수집 결과 (1) 1인 운영이 아닌 **다수 팀 협업** 구조, (2) 증빙 **만료 모델이 ISMS-P 실무와 부적합**, (3) **Framework 상속**으로 감사 주기 관리가 더 자연스럽다는 결론. **주요 변경**: ① **Framework 상속 모델 도입** (`frameworks.parent_framework_id` self-FK + `status`), 통제/증빙유형/수집작업 스냅샷 복제, 파일·이력 제외, ② **증빙 승인 플로우 도입** — `evidence_files.review_status` (pending/approved/rejected/auto_approved), `reviewed_by`, `review_note` (반려 필수), `reviewed_at`, `uploaded_by`, `submit_note`, ③ **담당자 협업 구조 명시화** — 역할 ENUM 변경 없이 `permission_evidence` + `owner_user_id` 조합으로 권한 제어, ④ **"내 할 일" 담당자 전용 페이지** 신설 (반려/마감임박/미제출/검토중/완료 5섹션), ⑤ **이메일 알림** (JavaMailSender) 도입, `notification_preferences` 테이블, 인앱 알림 벨 없음 ("내 할 일"이 그 역할), ⑥ **통제 항목 진입 UI 확정** — 2단계 흐름 (Framework 목록 페이지 → Framework 상세), 기존 스크린샷 레이아웃 100% 유지. **v10에서 폐기**: ❌ `evidence_types.renewal_cycle_days`, ❌ `evidence_files.valid_until`, ❌ 만료 임박 증빙 위젯 (만료 개념 자체 폐기). **스키마**: frameworks 2컬럼 추가, evidence_types 1컬럼 추가/1컬럼 제거, evidence_files 6컬럼 추가/1컬럼 제거, notification_preferences 신규, 테이블 9→10개. **로드맵**: Phase 5 전면 재작성 (5-1~5-9, 협업 체계 구축). **참고 프로토타입**: `secuhub_prototype_v4.html` (최종 확정 UI) |

---

*본 문서는 SecuHub 프로젝트의 기획 및 설계를 위한 기초 자료입니다.*
