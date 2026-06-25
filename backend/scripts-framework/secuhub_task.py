"""
SecuHub Collection Task — Template Method 진입점.

v18.9.12 신규. 사용자는 main(ctx) 함수에 @collect_task 적용만 — 프레임워크가
driver 생성/옵션/저장 경로/진단/cleanup 전부 처리. Scrapy 류의 선언적 + 함수 기반.

v19.17 — 진단 JSON 스키마를 selenium_wrapper.py 와 동일한 schema_version "1.0"
(execution / scenario / steps[] / diagnosis 4 영역) 으로 통일.
  - 회귀 원인: v18.9.12 도입 시 _diagnosis.json 을 자체 스키마
    ({version, status, error, current_step_when_failed, steps[label/duration_ms], env})
    로 산출 → FailureDiagnosisPanel.vue 의 parseDiagnosis 가 기대하는 v1.0
    (execution/scenario/steps[order,selenium_cmd,target,duration_sec,error{...}]/diagnosis)
    와 불일치 → 신규 @collect_task 패턴 스크립트는 성공/실패 무관하게 진단 패널 공백.
  - 정공 fix: 본 파일이 FE 계약(v1.0)을 그대로 준수하도록 _diagnosis.json 산출 변경.
    JobExecution.errorDiagnosis javadoc 의 "schema_version 1.0 — 4 영역" 계약 정합.
  - 추가: with ctx.step("...") 의 label AST 사전추출 → 실패 후 미실행 단계 not_run
    자동 채움 ("5/7 단계" parity, selenium_wrapper 와 동일 동작).
  - 추가: 실패 시 스크린샷(_diag_screenshot.png) + page_source(_diag_page_source.html)
    캡처 (ctx.driver 존재 시) — 진단 패널 우측 스크린샷/페이지소스 버튼 정합.
  - 추가: KOREAN_ERROR_MAP / PRIMARY_CAUSE_MAP — exception_class 단위 한국어 해석 + 추정 원인.

## 사용 패턴 (web_scraping)
    from secuhub_task import collect_task

    @collect_task(use_browser=True, headless=True)
    def main(ctx):
        with ctx.step("로그인"):
            ctx.driver.get("https://...")
        with ctx.step("저장"):
            (ctx.output / "result.csv").write_text(...)

## 사용 패턴 (excel/log 추출 — 브라우저 미사용)
    @collect_task(use_browser=False)
    def main(ctx):
        with ctx.step("..."): ...

## 프레임워크 책임
- ctx.driver  : webdriver | None (옵션·prefs·download 경로 자동 적용)
- ctx.output  : Path (SECUHUB_OUTPUT_DIR — 여기 저장하면 BE 자동 수집)
- ctx.step    : context manager (단계별 진단 — _diagnosis.json 산출)
- ctx.env     : dict (job_id, execution_id 등)
- driver.quit, 진단 JSON flush, exit code 처리

## 사용자 책임
- main(ctx) 함수 본문 (실제 추출 로직)
- @collect_task 옵션 선언 (필요한 만큼만, 전부 선택)

## step label 권장 형식 (진단 패널 표기 정합)
- label 의 첫 단어 = selenium_cmd, 나머지 = target 으로 자동 분리.
- selenium IDE 스타일 ("click button.login", "open /assets/list") 또는 한국어 동사 구
  ("로그인", "자산목록 저장") 모두 허용. 한국어 구는 첫 어절이 cmd 로 표기됨.
"""
import ast
import functools
import inspect
import json
import os
import shutil
import sys
import textwrap
import traceback
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Callable, Optional


# ──────────────────────────────────────────────────────────────────────
# 한국어 에러 매핑 + 추정 원인 (exception_class 단위 룰)
# selenium_wrapper.py 와 동일 키 셋 + 비-브라우저 작업(excel/log)에서 흔한 예외 보강.
# 운영 중 발견되는 새 패턴은 본 dict 에 추가만 하면 즉시 반영.
# ──────────────────────────────────────────────────────────────────────

KOREAN_ERROR_MAP = {
    # selenium 계열
    "NoSuchElementException":           "지정한 selector 의 요소가 페이지에 없습니다",
    "TimeoutException":                 "페이지 로드 또는 요소 대기가 시간 초과되었습니다",
    "ElementClickInterceptedException": "다른 요소가 가려서 클릭이 차단되었습니다",
    "ElementNotInteractableException":  "요소가 화면에 있지만 클릭/입력이 불가능한 상태입니다",
    "StaleElementReferenceException":   "참조한 요소가 페이지 갱신으로 무효화되었습니다",
    "WebDriverException":               "브라우저 드라이버 통신에서 문제가 발생했습니다",
    "InvalidSelectorException":         "selector 문법이 잘못되었습니다",
    "NoSuchDriverException":            "chromedriver 를 확보하지 못했습니다 (폐쇄망에서 Selenium Manager 의 외부 자동 다운로드가 차단됐을 가능성)",
    "SessionNotCreatedException":       "chrome driver 세션을 생성할 수 없습니다 (driver 버전 불일치 가능성)",
    # 파일/권한 (driver init + 비-브라우저 작업 공통)
    "FileNotFoundError":                "대상 파일 또는 바이너리를 찾을 수 없습니다",
    "PermissionError":                  "파일 또는 실행 바이너리의 접근/실행 권한이 없습니다",
    "IsADirectoryError":                "파일을 기대한 위치에 디렉토리가 있습니다",
    # 데이터 파싱 (excel/log/json 추출 작업)
    "KeyError":                         "기대한 키/컬럼이 데이터에 없습니다",
    "IndexError":                       "기대한 위치의 데이터가 없습니다 (인덱스 범위 초과)",
    "ValueError":                       "값의 형식이 기대와 다릅니다",
    "TypeError":                        "데이터 타입이 기대와 다릅니다",
    "UnicodeDecodeError":               "파일 인코딩을 해석하지 못했습니다 (인코딩 지정 필요)",
    "ConnectionError":                  "대상 서버에 연결하지 못했습니다",
}

PRIMARY_CAUSE_MAP = {
    "NoSuchElementException":           "사이트 UI 가 변경된 것 같습니다. {target} 가 다른 selector 로 바뀌었을 가능성 — 페이지 소스를 확인하세요.",
    "TimeoutException":                 "페이지 로드가 느리거나 네트워크 지연 가능성. 사이트 접속 자체가 가능한지 확인 후 timeout 조정.",
    "ElementClickInterceptedException": "팝업, 쿠키 동의, 광고 배너 등이 클릭을 가렸을 가능성. 스크린샷에서 가린 요소 확인.",
    "ElementNotInteractableException":  "요소가 disabled / hidden 상태일 가능성. 사전 조건 (로그인 / 권한) 충족 여부 확인.",
    "StaleElementReferenceException":   "페이지가 동적으로 갱신되어 요소가 교체되었습니다. 재조회 로직 추가 필요.",
    "WebDriverException":               "chromedriver 버전 불일치 또는 브라우저 충돌 가능성. 사내 미러의 driver 버전 확인.",
    "InvalidSelectorException":         "selector 표기 오류. CSS 또는 XPath 문법 점검.",
    "NoSuchDriverException":            "폐쇄망에서 Selenium Manager 가 외부(googlechromelabs.github.io)로 chromedriver 를 받으려다 실패했을 가능성이 큽니다. 사내 미러의 chromedriver 를 배치하고 CHROMEDRIVER_PATH 환경변수(또는 app.scripts.chromedriver-path 설정)로 경로를 지정하세요.",
    "SessionNotCreatedException":       "chromedriver 와 chromium 버전이 호환되지 않을 가능성. 사내 미러의 두 패키지 버전 확인.",
    "FileNotFoundError":                "경로가 잘못됐거나 대상 파일이 아직 생성되지 않았을 가능성. 절대/상대 경로와 선행 단계 산출물을 확인하세요.",
    "PermissionError":                  "파일 쓰기 권한 또는 실행 권한 누락. 저장 위치 권한과 chmod +x 여부를 확인하세요.",
    "KeyError":                         "원본 데이터의 컬럼/키 이름이 변경됐을 가능성. 실제 데이터 헤더를 확인하세요.",
    "IndexError":                       "원본 데이터의 행/열 수가 기대와 다를 가능성. 빈 결과 또는 형식 변경 여부 확인.",
    "ValueError":                       "파싱 대상 값의 형식(날짜/숫자 등)이 변경됐을 가능성. 원본 샘플을 확인하세요.",
    "UnicodeDecodeError":               "파일 인코딩이 UTF-8 이 아닐 가능성. open(..., encoding='cp949') 등 명시 필요.",
    "ConnectionError":                  "대상 서버 주소/포트 또는 폐쇄망 방화벽 정책 확인. 접속 자체 가능 여부 점검.",
}

DEFAULT_KOREAN_ERROR = "원인을 자동 판별하지 못했습니다. 원본 에러 메시지와 traceback 을 확인하세요."
DEFAULT_PRIMARY_CAUSE = "예외 종류가 매핑되지 않았습니다. 아래 원본 에러 메시지를 확인 후 운영자에게 문의하세요."

# 출력 파일명 (ScriptExecutionService / selenium_wrapper 와 정합)
DIAGNOSIS_FILENAME   = "_diagnosis.json"
SCREENSHOT_FILENAME  = "_diag_screenshot.png"
PAGE_SOURCE_FILENAME = "_diag_page_source.html"

# 한국 표준시 (KST)
KST = timezone(timedelta(hours=9))


def _split_label(label: str) -> tuple[str, str]:
    """label 의 첫 어절 = selenium_cmd, 나머지 = target (selenium_wrapper 와 동일 규약)."""
    parts = label.split(maxsplit=1)
    selenium_cmd = parts[0] if parts else ""
    target = parts[1] if len(parts) > 1 else ""
    return selenium_cmd, target


# ──────────────────────────────────────────────────────────────────────
# v19.17 — with ctx.step("...") 의 label AST 사전추출 (not_run 자동 채움용)
# selenium_wrapper._extract_step_labels 의 secuhub_task 판(메서드 호출 형태) 대응.
# ──────────────────────────────────────────────────────────────────────

def _extract_step_labels(fn: Callable) -> list[str]:
    """
    main(ctx) 함수 AST 를 분석하여 `with <var>.step("...")` 블록의 label 사전 추출.

    실패 후 실행 안 된 step 들을 _diagnosis.json 에 not_run 으로 채워 "X/Y 단계" 표기 정합.
    AST 추출 실패(lambda / REPL / source 접근 불가 등) 시 빈 리스트 — graceful fallback.

    제약:
    - `with <var>.step("...")` 의 첫 인자가 문자열 리터럴(ast.Constant) 인 경우만 추출.
      (ctx 라는 파라미터명에 의존하지 않음 — attr == "step" 인 모든 메서드 호출 매칭)
    - `with ctx.step(label_variable)` / `with ctx.step(f"...")` 는 추출 불가(해당 step skip).
    - 같은 `with` 문에 step 이외 context manager 가 섞여 있어도 step 만 골라 추출.
    """
    try:
        source = textwrap.dedent(inspect.getsource(fn))
        tree = ast.parse(source)

        labels: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.With):
                for item in node.items:
                    cm = item.context_expr
                    if (isinstance(cm, ast.Call)
                            and isinstance(cm.func, ast.Attribute)
                            and cm.func.attr == "step"
                            and cm.args
                            and isinstance(cm.args[0], ast.Constant)
                            and isinstance(cm.args[0].value, str)):
                        labels.append(cm.args[0].value)
                        break  # 같은 with 안 다른 context manager 무시
        return labels
    except (OSError, TypeError, SyntaxError) as e:
        print(f"[secuhub_task] AST step 라벨 추출 실패 (graceful fallback): {e}", file=sys.stderr)
        return []
    except Exception as e:
        print(f"[secuhub_task] AST step 라벨 추출 중 예상치 못한 예외: {e}", file=sys.stderr)
        return []


# ============================================================================
# Context — 사용자 main(ctx) 에 주입되는 컨텍스트 객체
# ============================================================================
class _Context:
    """
    main(ctx) 안에서 사용. 사용자는 직접 인스턴스화하지 않음.

    v19.17 — 진단 누적을 schema_version "1.0" step 형식으로 직접 보관.
    """

    def __init__(self, output: Path, env: dict, driver=None,
                 defined_labels: Optional[list[str]] = None):
        self.driver = driver       # webdriver | None
        self.output = output       # Path — 결과 저장 디렉토리
        self.env = env             # job_id, execution_id, etc.

        # 진단 누적 (v1.0 step 형식)
        self._steps: list[dict] = []
        self._current_order = 0
        self._current_label: Optional[str] = None
        self._failed = False
        self._artifacts_captured = False
        self.started_at = datetime.now(KST)
        # AST 사전추출 라벨 (not_run 채움용)
        self.defined_labels: list[str] = defined_labels or []

    @contextmanager
    def step(self, label: str):
        """
        단계별 진단 context manager.

            with ctx.step("로그인"):
                ctx.driver.get("...")

        시작/종료 시각·소요시간·예외 여부를 v1.0 step 형식으로 기록.
        cleanup 시점에 ctx.output/_diagnosis.json 으로 flush.
        """
        self._current_order += 1
        order = self._current_order
        selenium_cmd, target = _split_label(label)
        started = datetime.now(KST)

        entry = {
            "order": order,
            "label": label,
            "selenium_cmd": selenium_cmd,
            "target": target,
            "status": "running",
            "started_at": started.isoformat(),
        }
        self._steps.append(entry)
        self._current_label = label
        try:
            yield
            ended = datetime.now(KST)
            entry["status"] = "success"
            entry["ended_at"] = ended.isoformat()
            entry["duration_sec"] = round((ended - started).total_seconds(), 2)
        except Exception as exc:
            ended = datetime.now(KST)
            exception_class = type(exc).__name__
            entry["status"] = "failed"
            entry["ended_at"] = ended.isoformat()
            entry["duration_sec"] = round((ended - started).total_seconds(), 2)
            entry["error"] = {
                "exception_class": exception_class,
                "korean_message":  KOREAN_ERROR_MAP.get(exception_class, DEFAULT_KOREAN_ERROR),
                "selector":        target,
                "raw_message":     str(exc)[:500],
                "current_url":     self._safe_current_url(),
            }
            self._failed = True
            # 실패 시점 스크린샷 + page_source (1회만)
            self._capture_failure_artifacts()
            raise
        finally:
            self._current_label = None

    def _safe_current_url(self) -> str:
        """driver.current_url 호출 실패 시 빈 문자열."""
        if self.driver is None:
            return ""
        try:
            return self.driver.current_url
        except Exception:
            return ""

    def _capture_failure_artifacts(self) -> None:
        """실패 시점 스크린샷 + page_source 저장 (driver 존재 시, 1회만)."""
        if self.driver is None or self._artifacts_captured:
            return
        self._artifacts_captured = True
        try:
            self.driver.save_screenshot(str(self.output / SCREENSHOT_FILENAME))
        except Exception as e:
            print(f"[secuhub_task] 스크린샷 저장 실패: {e}", file=sys.stderr)
        try:
            (self.output / PAGE_SOURCE_FILENAME).write_text(
                self.driver.page_source, encoding="utf-8")
        except Exception as e:
            print(f"[secuhub_task] page_source 저장 실패: {e}", file=sys.stderr)

    # ── 진단 JSON flush ─────────────────────────────────────────────────
    def flush_diagnostics(self, overall_status: str, scenario_name: str,
                          unwrapped_error: Optional[Exception] = None) -> None:
        """
        cleanup 시 v1.0 진단 JSON 산출. BE collectDiagnosis 가 outputDir/_diagnosis.json
        감지 후 JobExecution.errorDiagnosis 저장 → FailureDiagnosisPanel 이 그대로 렌더.

        overall_status: "success" | "failed"
        """
        # step() 밖에서 발생한 예외(드물지만 가능) — 미분류 실패 step 1개 추가
        if overall_status == "failed" and not self._failed and unwrapped_error is not None:
            exc = unwrapped_error
            now = datetime.now(KST)
            self._current_order += 1
            self._steps.append({
                "order": self._current_order,
                "label": "(unwrapped exception)",
                "selenium_cmd": "",
                "target": "",
                "status": "failed",
                "started_at": now.isoformat(),
                "ended_at": now.isoformat(),
                "duration_sec": 0.0,
                "error": {
                    "exception_class": type(exc).__name__,
                    "korean_message":  KOREAN_ERROR_MAP.get(type(exc).__name__, DEFAULT_KOREAN_ERROR),
                    "selector":        "",
                    "raw_message":     str(exc)[:500],
                    "current_url":     self._safe_current_url(),
                },
            })
            self._failed = True
            self._capture_failure_artifacts()

        # 실패 시 — 정의된 라벨 중 실행 안 된 것들 not_run 자동 채움 (AST 성공 시)
        if overall_status == "failed" and self.defined_labels:
            already = len(self._steps)
            defined_count = len(self.defined_labels)
            if defined_count > already:
                for i in range(already, defined_count):
                    label = self.defined_labels[i]
                    selenium_cmd, target = _split_label(label)
                    self._steps.append({
                        "order": i + 1,
                        "label": label,
                        "selenium_cmd": selenium_cmd,
                        "target": target,
                        "status": "not_run",
                    })

        # total_steps = 정의된 총 단계 수 우선, 없으면 실행된 단계 수
        total_steps = max(len(self.defined_labels), len(self._steps))

        # primary_cause 자동 추론 — 실패 단계의 exception_class 매핑
        primary_cause = None
        if overall_status == "failed":
            failed_step = next((s for s in self._steps if s["status"] == "failed"), None)
            if failed_step and "error" in failed_step:
                exc_class = failed_step["error"]["exception_class"]
                target = failed_step["error"].get("selector", "")
                template = PRIMARY_CAUSE_MAP.get(exc_class, DEFAULT_PRIMARY_CAUSE)
                try:
                    primary_cause = template.format(target=target or "(target 정보 없음)")
                except (KeyError, IndexError):
                    primary_cause = template

        ended_at = datetime.now(KST)
        total_duration = round((ended_at - self.started_at).total_seconds(), 2)

        diagnosis = {
            "schema_version": "1.0",
            "execution": {
                "started_at":   self.started_at.isoformat(),
                "ended_at":     ended_at.isoformat(),
                "duration_sec": total_duration,
                "status":       overall_status,
            },
            "scenario": {
                "name": scenario_name,
                "total_steps": total_steps,
            },
            "steps": self._steps,
            "diagnosis": {
                "primary_cause":    primary_cause,
                "screenshot_path":  SCREENSHOT_FILENAME if (overall_status == "failed" and self._artifacts_captured) else None,
                "page_source_path": PAGE_SOURCE_FILENAME if (overall_status == "failed" and self._artifacts_captured) else None,
            },
        }
        try:
            (self.output / DIAGNOSIS_FILENAME).write_text(
                json.dumps(diagnosis, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            print(f"[secuhub_task] 진단 JSON 저장: {self.output / DIAGNOSIS_FILENAME}", file=sys.stderr)
        except Exception as e:
            # 진단 산출 실패는 silent — 본 흐름에 영향 없음
            print(f"[secuhub_task] _diagnosis.json 산출 실패: {e}", file=sys.stderr)


def _write_driver_init_failure(output_dir: Path, exc: Exception,
                               scenario_name: str, defined_labels: list[str]) -> None:
    """
    v19.17 — chrome driver 초기화 실패 시 v1.0 진단 JSON 작성.

    driver 가 없어 스크린샷/page_source 불가. 1단계="chrome driver 초기화" 실패,
    이후 단계들=AST 추출 label 그대로 not_run. execution.status="failed" (FE 가
    'success'|'failed' 만 인식하므로 driver_init 도 failed 로 매핑).
    """
    now = datetime.now(KST)
    exception_class = type(exc).__name__
    korean_message = KOREAN_ERROR_MAP.get(exception_class, DEFAULT_KOREAN_ERROR)
    primary_cause = PRIMARY_CAUSE_MAP.get(exception_class, DEFAULT_PRIMARY_CAUSE)
    try:
        primary_cause = primary_cause.format(target="(driver 초기화)")
    except (KeyError, IndexError):
        pass

    init_step = {
        "order": 1,
        "label": "chrome driver 초기화",
        "selenium_cmd": "init",
        "target": "chromedriver",
        "status": "failed",
        "started_at": now.isoformat(),
        "ended_at": now.isoformat(),
        "duration_sec": 0.0,
        "error": {
            "exception_class": exception_class,
            "korean_message":  "chrome driver 초기화에 실패했습니다. " + korean_message,
            "selector":        "",
            "raw_message":     str(exc)[:500],
            "current_url":     "",
        },
    }
    steps: list[dict] = [init_step]
    for i, label in enumerate(defined_labels):
        selenium_cmd, target = _split_label(label)
        steps.append({
            "order": i + 2,
            "label": label,
            "selenium_cmd": selenium_cmd,
            "target": target,
            "status": "not_run",
        })

    diagnosis = {
        "schema_version": "1.0",
        "execution": {
            "started_at":   now.isoformat(),
            "ended_at":     now.isoformat(),
            "duration_sec": 0.0,
            "status":       "failed",
        },
        "scenario": {"name": scenario_name, "total_steps": len(steps)},
        "steps": steps,
        "diagnosis": {
            "primary_cause":    primary_cause,
            "screenshot_path":  None,
            "page_source_path": None,
        },
    }
    try:
        (output_dir / DIAGNOSIS_FILENAME).write_text(
            json.dumps(diagnosis, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[secuhub_task] driver 초기화 실패 — 진단 JSON 저장: {output_dir / DIAGNOSIS_FILENAME}",
              file=sys.stderr)
    except Exception as write_exc:
        print(f"[secuhub_task] driver 초기화 실패 진단 JSON 쓰기도 실패: {write_exc}", file=sys.stderr)


# ============================================================================
# v19.18 — chromedriver / chrome 바이너리 경로 자동 해석 (폐쇄망 정합)
#
# 폐쇄망에서는 Selenium Manager 의 외부 자동 다운로드(googlechromelabs.github.io)가
# 불가하므로, driver 를 절대 Selenium Manager 에 맡기지 않는다. 아래 순서로 직접 해석:
#   1) CHROMEDRIVER_PATH 환경변수 (BE 주입 또는 수동 export)
#   2) 알려진 후보 경로 (사내 고정 배포 위치)
#   3) PATH 의 chromedriver
# 셋 다 실패하면 외부로 나가지 않고 명확한 한국어 메시지로 즉시 실패.
# (chrome 바이너리도 동일 방식으로 탐색 — 비표준 위치면 binary_location 주입)
# ============================================================================

# 사내 고정 배포 위치. 운영 경로가 바뀌면 이 목록에 추가만 하면 됨.
_CANDIDATE_CHROMEDRIVER_PATHS = [
    "/home/dnbapp/SEMS/chromedriver-linux64/chromedriver",
    "/var/lib/secuhub/chromedriver-linux64/chromedriver",
    "/usr/local/bin/chromedriver",
    "/usr/bin/chromedriver",
    "/opt/chromedriver-linux64/chromedriver",
]

_CANDIDATE_CHROME_BINARIES = [
    "/usr/bin/google-chrome",
    "/usr/bin/google-chrome-stable",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/opt/google/chrome/chrome",
]


def _is_executable_file(path: str) -> bool:
    return bool(path) and os.path.isfile(path) and os.access(path, os.X_OK)


def _resolve_chromedriver() -> Optional[str]:
    """chromedriver 실행 파일 경로 해석. Selenium Manager(외부 다운로드)는 절대 사용 안 함."""
    # 1) 환경변수 (BE 의 CHROMEDRIVER_PATH 주입 또는 수동 export)
    env_path = os.environ.get("CHROMEDRIVER_PATH", "").strip()
    if _is_executable_file(env_path):
        return env_path
    # 2) 알려진 후보 경로
    for c in _CANDIDATE_CHROMEDRIVER_PATHS:
        if _is_executable_file(c):
            return c
    # 3) PATH
    found = shutil.which("chromedriver")
    if found and _is_executable_file(found):
        return found
    return None


def _resolve_chrome_binary() -> Optional[str]:
    """chrome/chromium 바이너리 경로 해석 (선택 — 표준 위치면 None 반환해도 무방)."""
    env_bin = os.environ.get("CHROME_BINARY", "").strip()
    if _is_executable_file(env_bin):
        return env_bin
    for c in _CANDIDATE_CHROME_BINARIES:
        if _is_executable_file(c):
            return c
    for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser"):
        found = shutil.which(name)
        if found and _is_executable_file(found):
            return found
    return None


# ============================================================================
# driver 생성 — 운영 옵션 + 사용자 옵션 merge + download 경로 자동 주입
# ============================================================================
def _build_driver(
    headless: bool,
    chrome_options: Optional[list[str]],
    chrome_prefs: Optional[dict],
    output_dir: Path,
):
    """
    Chrome webdriver 생성. 운영 기본값 + 사용자 옵션 merge.

    - headless=True 시 --headless=new 자동
    - --no-sandbox / --disable-dev-shm-usage (Linux 운영 안정성)
    - download.default_directory = output_dir (다운로드 자동 가로채기)
    - 사용자 chrome_options / chrome_prefs 는 그 위에 추가
    - v19.18: driver 를 _resolve_chromedriver() 로 직접 해석 후 Service 로만 기동.
      Selenium Manager(외부 다운로드)로 절대 fallback 하지 않음. 못 찾으면 명확히 실패.
    """
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service

    opts = Options()

    # ── 운영 기본 옵션 (headless 토글) ──
    if headless:
        opts.add_argument("--headless=new")
    opts.add_argument("--no-sandbox")
    opts.add_argument("--disable-dev-shm-usage")

    # ── 사용자 추가 옵션 (자유) ──
    for arg in (chrome_options or []):
        opts.add_argument(arg)

    # ── chrome 바이너리 비표준 위치면 명시 ──
    chrome_bin = _resolve_chrome_binary()
    if chrome_bin:
        opts.binary_location = chrome_bin

    # ── 다운로드 경로 자동 주입 (사용자 가로채기 핵심) ──
    base_prefs = {
        "download.default_directory": str(output_dir.resolve()),
        "download.prompt_for_download": False,
        "download.directory_upgrade": True,
        "safebrowsing.enabled": True,
    }
    # 사용자 prefs 는 base 위에 merge (사용자가 download.default_directory override 하지 않는 한 보존)
    base_prefs.update(chrome_prefs or {})
    opts.add_experimental_option("prefs", base_prefs)

    # ── driver 경로 직접 해석 (Selenium Manager 차단) ──
    driver_path = _resolve_chromedriver()
    if not driver_path:
        raise RuntimeError(
            "chromedriver 를 찾을 수 없습니다. 폐쇄망에서는 Selenium Manager 의 "
            "외부 자동 다운로드가 불가합니다. CHROMEDRIVER_PATH 환경변수, "
            "app.scripts.chromedriver-path 설정, 또는 아래 후보 경로 중 하나에 "
            "사내 미러 chromedriver 를 배치하세요. 확인한 후보: "
            + ", ".join(_CANDIDATE_CHROMEDRIVER_PATHS)
        )

    print(f"[secuhub_task] chromedriver 사용: {driver_path}"
          + (f" / chrome: {chrome_bin}" if chrome_bin else ""), file=sys.stderr)

    service = Service(executable_path=driver_path)
    return webdriver.Chrome(service=service, options=opts)


# ============================================================================
# @collect_task 데코레이터
# ============================================================================
def collect_task(
    use_browser: bool = True,
    headless: bool = True,
    chrome_options: Optional[list[str]] = None,
    chrome_prefs: Optional[dict] = None,
):
    """
    Template Method 진입점. 사용자는 main(ctx) 함수에 본 데코레이터 적용.

    데코레이터 적용 시점에 즉시 실행 — 사용자가 별도 main() 호출 또는
    if __name__ == "__main__" 작성 불필요.

    Args:
        use_browser  : webdriver 필요 여부. False 시 ctx.driver = None
        headless     : 운영 기본값 True. 로컬 디버깅 시 False 가능
        chrome_options : ["--window-size=1920,1080", ...] 추가 옵션 (선택)
        chrome_prefs   : {"profile.default_content_settings.popups": 0, ...} (선택)
    """

    def decorator(fn: Callable[[Any], None]):
        # ── 환경 변수 (BE 가 주입) ──
        output_dir = Path(os.environ.get("SECUHUB_OUTPUT_DIR", "."))
        env = {
            "job_id": os.environ.get("SECUHUB_JOB_ID"),
            "execution_id": os.environ.get("SECUHUB_EXECUTION_ID"),
        }
        output_dir.mkdir(parents=True, exist_ok=True)

        scenario_name = getattr(fn, "__name__", "main")

        # ── v19.17 — step label AST 사전추출 (driver 생성 전에도 가능) ──
        defined_labels = _extract_step_labels(fn)

        # ── driver 생성 (use_browser 분기) ──
        driver = None
        if use_browser:
            try:
                driver = _build_driver(headless, chrome_options, chrome_prefs, output_dir)
            except Exception as e:
                # driver init 실패도 v1.0 진단으로 기록 (selenium_wrapper 흐름 정합)
                _write_driver_init_failure(output_dir, e, scenario_name, defined_labels)
                print(f"[secuhub_task] driver 초기화 실패: {e}", file=sys.stderr)
                traceback.print_exc()
                sys.exit(1)

        # ── ctx 구성 + 사용자 main(ctx) 호출 ──
        ctx = _Context(output_dir, env, driver=driver, defined_labels=defined_labels)
        overall_status = "success"
        unwrapped_error: Optional[Exception] = None
        try:
            fn(ctx)
        except SystemExit:
            raise
        except Exception as e:
            overall_status = "failed"
            unwrapped_error = e
            print(f"[secuhub_task] main 실패: {e}", file=sys.stderr)
            traceback.print_exc()
        finally:
            # 진단 flush (v1.0 스키마)
            ctx.flush_diagnostics(overall_status, scenario_name, unwrapped_error=unwrapped_error)
            # driver cleanup
            if driver is not None:
                try:
                    driver.quit()
                except Exception:
                    pass

        # 실패 시 exit code 1 (BE 가 status 판정)
        if overall_status != "success":
            sys.exit(1)

        # 데코레이터 본래 함수 반환 (다른 코드에서 호출 가능 — 테스트 용)
        @functools.wraps(fn)
        def passthrough(ctx_arg):
            return fn(ctx_arg)

        return passthrough

    return decorator