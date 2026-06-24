"""
SecuHub v18.7 — selenium 자동 수집 wrapper template
v18.8.5 — 3 버그 fix (driver init 보호 + AST not_run + total_steps 정정)

관리자 사용법 (한 줄 추가):

    from selenium_wrapper import execute_with_diagnosis, step

    def scenario(driver, output_dir):
        with step("open .../login"):
            driver.get("https://example.com/login")
        with step("type id=user-id"):
            driver.find_element(By.ID, "user-id").send_keys("admin")
        with step("click button.login-submit"):
            driver.find_element(By.CSS_SELECTOR, "button.login-submit").click()
        # ... 자기 selenium 코드 자유롭게 작성, step() 으로 감싸기만

    execute_with_diagnosis(scenario)

wrapper 가 자동 처리:
- chrome driver 생성 (headless + 폐쇄망 옵션)
- step 단위 추적 (시작/종료 시간 + 상태)
- 예외 시: 스크린샷 + page_source + 한국어 매핑 + 추정 원인 자동 저장
- output_dir 에 _diagnosis.json + _diag_screenshot.png + _diag_page_source.html 산출
- driver.quit() (성공/실패 무관)

v18.8.5 변경점:
1. chrome driver 초기화 실패 시에도 진단 JSON 생성 (옛: 진단 없이 그대로 crash)
2. scenario 함수 AST 파싱 → 정의된 모든 step 의 label 사전 추출 → 실행 안 된 step 들은
   _diagnosis.json 에 status="not_run" 으로 자동 채움 (mockup 의 "5/7 단계" 표기 정합)
3. scenario.total_steps 가 "실행된 단계 수" → "정의된 총 단계 수" 로 정정

ScriptExecutionService.collectOutputFiles() 가 output_dir 에서 _diagnosis.json 감지 + JobExecution.errorDiagnosis 에 저장.
어드민 UI 진단 패널이 errorDiagnosis JSON 그대로 렌더링.

L_USER_NEEDS_REDIRECT 정합 — 본질 needs = 실패 진단. visual builder (~8주) 대신 execution debugger (~2~3주).
L_CLOSED_NETWORK_DEPENDENCY 정합 — Rocky 8.9 사내 RPM 미러 + chromedriver tarball + selenium wheel 사내 PyPI 가정.
"""

import ast
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
from typing import Callable, Optional

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import (
    NoSuchElementException,
    TimeoutException,
    ElementClickInterceptedException,
    ElementNotInteractableException,
    StaleElementReferenceException,
    WebDriverException,
    InvalidSelectorException,
)


# ──────────────────────────────────────────────────────────────────────
# 한국어 에러 매핑 + 추정 원인 (exception_class 단위 룰)
# 운영 중 발견되는 새 패턴은 본 dict 에 추가만 하면 즉시 반영.
# ──────────────────────────────────────────────────────────────────────

KOREAN_ERROR_MAP = {
    "NoSuchElementException":           "지정한 selector 의 요소가 페이지에 없습니다",
    "TimeoutException":                 "페이지 로드 또는 요소 대기가 시간 초과되었습니다",
    "ElementClickInterceptedException": "다른 요소가 가려서 클릭이 차단되었습니다",
    "ElementNotInteractableException":  "요소가 화면에 있지만 클릭/입력이 불가능한 상태입니다",
    "StaleElementReferenceException":   "참조한 요소가 페이지 갱신으로 무효화되었습니다",
    "WebDriverException":               "브라우저 드라이버 통신에서 문제가 발생했습니다",
    "InvalidSelectorException":         "selector 문법이 잘못되었습니다",
    "NoSuchDriverException":            "chromedriver 를 확보하지 못했습니다 (폐쇄망에서 Selenium Manager 의 외부 자동 다운로드가 차단됐을 가능성)",
    # v18.8.5 — driver init 시점에 흔히 발생하는 예외 추가
    "SessionNotCreatedException":       "chrome driver 세션을 생성할 수 없습니다 (driver 버전 불일치 가능성)",
    "FileNotFoundError":                "chromedriver 또는 chrome/chromium 바이너리를 찾을 수 없습니다",
    "PermissionError":                  "chromedriver / chrome 바이너리의 실행 권한이 없습니다",
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
    "FileNotFoundError":                "chromedriver 또는 chrome/chromium 가 설치되지 않았거나 PATH 에 없습니다. CHROMEDRIVER_PATH 환경변수 확인.",
    "PermissionError":                  "chromedriver / chrome 실행 권한 누락. chmod +x 또는 sudo 설치 필요.",
}

DEFAULT_KOREAN_ERROR = "원인을 자동 판별하지 못했습니다. 페이지 소스와 스크린샷을 확인하세요."
DEFAULT_PRIMARY_CAUSE = "예외 종류가 매핑되지 않았습니다. 운영자에게 문의하세요."

# 출력 파일명 (ScriptExecutionService 와 정합)
DIAGNOSIS_FILENAME       = "_diagnosis.json"
SCREENSHOT_FILENAME      = "_diag_screenshot.png"
PAGE_SOURCE_FILENAME     = "_diag_page_source.html"

# 한국 표준시 (KST)
KST = timezone(timedelta(hours=9))


# ──────────────────────────────────────────────────────────────────────
# 내부 상태 — step context manager 가 _CURRENT 에 결과 누적
# ──────────────────────────────────────────────────────────────────────

class _ExecutionContext:
    """wrapper 실행 1회당 1 인스턴스. step() 이 본 context 에 추적 정보 누적."""

    def __init__(self, driver, output_dir: Path, defined_labels: list[str]):
        self.driver = driver
        self.output_dir = output_dir
        self.started_at = datetime.now(KST)
        self.steps: list[dict] = []
        self.current_order = 0
        self.failed = False
        # v18.8.5 — AST 로 사전 추출한 step label 들 (not_run 채우기용)
        self.defined_labels: list[str] = defined_labels

    def add_step(self, step_info: dict) -> None:
        self.steps.append(step_info)
        if step_info["status"] == "failed":
            self.failed = True


# ScriptExecutionService 의 단일 프로세스 호출 모델 가정 — module-level 1 슬롯.
_CURRENT: Optional[_ExecutionContext] = None


# ──────────────────────────────────────────────────────────────────────
# v18.8.5 — AST 기반 step label 사전 추출 (not_run 자동 채움용)
# ──────────────────────────────────────────────────────────────────────

def _extract_step_labels(scenario_func: Callable) -> list[str]:
    """
    scenario 함수의 AST 를 분석하여 `with step("...")` 블록의 label 들을 사전 추출.

    실패 후 실행 안 된 step 들을 _diagnosis.json 에 not_run 으로 채우기 위한 정보 수집.
    AST 추출 실패 시 (lambda / REPL / source 접근 불가 등) 빈 리스트 반환 — graceful fallback.

    제약:
    - `with step("...")` 의 첫 인자가 ast.Constant (문자열 리터럴) 인 경우만 추출
    - `with step(label_variable)` 또는 `with step(f"...")` 는 라벨 추출 불가 (해당 step 만 skip)
    - 같은 `with` 문에 step 이외 context manager 가 섞여 있어도 step 만 골라서 추출

    Returns:
        scenario 안의 모든 `with step("...")` 의 label 문자열 리스트 (정의 순서)
    """
    try:
        source = textwrap.dedent(inspect.getsource(scenario_func))
        tree = ast.parse(source)

        labels: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.With):
                for item in node.items:
                    cm = item.context_expr
                    if (isinstance(cm, ast.Call)
                            and isinstance(cm.func, ast.Name)
                            and cm.func.id == "step"
                            and cm.args
                            and isinstance(cm.args[0], ast.Constant)
                            and isinstance(cm.args[0].value, str)):
                        labels.append(cm.args[0].value)
                        # 같은 `with` 안의 다른 context manager 는 무시
                        break
        return labels
    except (OSError, TypeError, SyntaxError) as e:
        print(f"[wrapper] AST step 라벨 추출 실패 (graceful fallback): {e}", file=sys.stderr)
        return []
    except Exception as e:
        # 예상 못 한 예외 — 본 흐름 차단 금지
        print(f"[wrapper] AST step 라벨 추출 중 예상치 못한 예외: {e}", file=sys.stderr)
        return []


def _split_label(label: str) -> tuple[str, str]:
    """label 의 첫 단어 = selenium_cmd, 나머지 = target."""
    parts = label.split(maxsplit=1)
    selenium_cmd = parts[0] if parts else ""
    target = parts[1] if len(parts) > 1 else ""
    return selenium_cmd, target


# ──────────────────────────────────────────────────────────────────────
# step() context manager — 관리자가 사용하는 핵심 API
# ──────────────────────────────────────────────────────────────────────

@contextmanager
def step(label: str):
    """
    selenium 액션 1개를 진단 단위로 감쌈.

    label 형식 권장 — selenium IDE 명령어 그대로:
        "open .../login"
        "type id=user-id"
        "click button.login-submit"
        "storeText table#assets-table"

    label 의 첫 단어 = selenium_cmd, 나머지 = target. wrapper 가 자동 분리.
    """
    if _CURRENT is None:
        raise RuntimeError(
            "step() 은 execute_with_diagnosis() 안에서만 호출 가능합니다. "
            "관리자 시나리오의 마지막 줄에 execute_with_diagnosis(scenario) 추가 필요."
        )

    _CURRENT.current_order += 1
    order = _CURRENT.current_order

    selenium_cmd, target = _split_label(label)

    step_started = datetime.now(KST)
    step_info = {
        "order": order,
        "label": label,
        "selenium_cmd": selenium_cmd,
        "target": target,
        "status": "running",
        "started_at": step_started.isoformat(),
    }

    try:
        yield
    except Exception as exc:
        # 실패 — 진단 정보 캡쳐
        step_ended = datetime.now(KST)
        duration = (step_ended - step_started).total_seconds()

        exception_class = type(exc).__name__
        korean_message = KOREAN_ERROR_MAP.get(exception_class, DEFAULT_KOREAN_ERROR)

        step_info.update({
            "status": "failed",
            "ended_at": step_ended.isoformat(),
            "duration_sec": round(duration, 2),
            "error": {
                "exception_class": exception_class,
                "korean_message":  korean_message,
                "selector":        target,
                "raw_message":     str(exc)[:500],   # 잘림 방지
                "current_url":     _safe_get_current_url(),
            },
        })
        _CURRENT.add_step(step_info)

        # 스크린샷 + page_source 캡쳐 (실패 단계 1회만)
        _capture_failure_artifacts(_CURRENT)

        raise   # 예외 그대로 전파 → execute_with_diagnosis 가 catch 후 _diagnosis.json 마무리

    else:
        # 성공
        step_ended = datetime.now(KST)
        duration = (step_ended - step_started).total_seconds()
        step_info.update({
            "status": "success",
            "ended_at": step_ended.isoformat(),
            "duration_sec": round(duration, 2),
        })
        _CURRENT.add_step(step_info)


def _safe_get_current_url() -> str:
    """driver.current_url 호출 실패 시 빈 문자열 반환."""
    if _CURRENT is None or _CURRENT.driver is None:
        return ""
    try:
        return _CURRENT.driver.current_url
    except Exception:
        return ""


def _capture_failure_artifacts(ctx: _ExecutionContext) -> None:
    """실패 시점 스크린샷 + page_source 저장. 어떤 단계든 1회만 캡쳐."""
    if ctx.driver is None:
        return

    screenshot_path = ctx.output_dir / SCREENSHOT_FILENAME
    page_source_path = ctx.output_dir / PAGE_SOURCE_FILENAME

    try:
        ctx.driver.save_screenshot(str(screenshot_path))
    except Exception as e:
        print(f"[wrapper] 스크린샷 저장 실패: {e}", file=sys.stderr)

    try:
        page_source = ctx.driver.page_source
        page_source_path.write_text(page_source, encoding="utf-8")
    except Exception as e:
        print(f"[wrapper] page_source 저장 실패: {e}", file=sys.stderr)


# ──────────────────────────────────────────────────────────────────────
# execute_with_diagnosis() — 관리자가 호출하는 entry point
# ──────────────────────────────────────────────────────────────────────

def execute_with_diagnosis(scenario: Callable, *, output_dir: Optional[str] = None) -> int:
    """
    selenium 시나리오를 실행하고 진단 JSON 을 출력 디렉토리에 저장.

    Args:
        scenario: 관리자가 작성한 함수, signature = (driver, output_dir: Path) -> None.
                  내부에서 step() context manager 로 각 액션 감싸기.
        output_dir: 출력 디렉토리. None 이면 sys.argv[1] 사용 (ScriptExecutionService 호출 컨벤션).

    Returns:
        exit code — 성공 0, 실패 1. ScriptExecutionService.buildCommand 의 종료 코드 컨벤션 정합.
    """
    global _CURRENT

    # output_dir 결정 — argv[1] 우선 (운영 환경 표준)
    if output_dir is None:
        if len(sys.argv) < 2:
            print("[wrapper] output_dir 가 지정되지 않았습니다. sys.argv[1] 또는 output_dir 파라미터 필요.", file=sys.stderr)
            return 2
        output_dir = sys.argv[1]

    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    scenario_name = getattr(scenario, "__name__", "scenario")

    # v18.8.5 — AST 로 사전 추출 (driver 생성 전이라도 가능)
    defined_labels = _extract_step_labels(scenario)

    # ──────────────────────────────────────────────────────────────────
    # v18.8.5 — driver 초기화 실패 시에도 진단 JSON 작성
    # 옛: _build_driver() 실패 시 예외 그대로 propagate → 진단 JSON 미생성 → FE 정보 없음
    # 새: try/except 로 감싸고, 실패 시 단순 진단 JSON 작성 후 exit 1
    # ──────────────────────────────────────────────────────────────────
    try:
        driver = _build_driver()
    except Exception as driver_init_exc:
        _write_driver_init_failure(
            output_dir=out,
            exc=driver_init_exc,
            scenario_name=scenario_name,
            defined_labels=defined_labels,
        )
        traceback.print_exc(file=sys.stderr)
        return 1

    ctx = _ExecutionContext(driver=driver, output_dir=out, defined_labels=defined_labels)
    _CURRENT = ctx

    try:
        scenario(driver, out)
        # 성공 — 진단 JSON 저장 (성공 시도 단계 정보는 유지)
        _write_diagnosis_json(ctx, status="success", scenario_name=scenario_name, error=None)
        return 0

    except Exception as exc:
        # 실패 — step() 안에서 raise 된 경우 step_info 가 이미 추가됨
        # step() 밖에서 발생한 예외 (드물지만 가능) 도 처리
        if not ctx.failed:
            # step 밖 예외 — 미분류
            ctx.steps.append({
                "order": ctx.current_order + 1,
                "label": "(unwrapped exception)",
                "selenium_cmd": "",
                "target": "",
                "status": "failed",
                "started_at": datetime.now(KST).isoformat(),
                "ended_at": datetime.now(KST).isoformat(),
                "duration_sec": 0.0,
                "error": {
                    "exception_class": type(exc).__name__,
                    "korean_message":  KOREAN_ERROR_MAP.get(type(exc).__name__, DEFAULT_KOREAN_ERROR),
                    "selector":        "",
                    "raw_message":     str(exc)[:500],
                    "current_url":     _safe_get_current_url(),
                },
            })
            _capture_failure_artifacts(ctx)

        _write_diagnosis_json(ctx, status="failed", scenario_name=scenario_name, error=exc)
        # exception trace 는 stderr 로 (ScriptExecutionService 로그에 보존)
        traceback.print_exc(file=sys.stderr)
        return 1

    finally:
        try:
            driver.quit()
        except Exception:
            pass
        _CURRENT = None


# ──────────────────────────────────────────────────────────────────────
# 내부 helper — chrome driver 생성 + 진단 JSON 직렬화
# ──────────────────────────────────────────────────────────────────────

def _is_executable_file(path: str) -> bool:
    return bool(path) and os.path.isfile(path) and os.access(path, os.X_OK)


# 사내 고정 배포 위치 (secuhub_task.py 와 동일 목록 유지).
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


def _resolve_chromedriver() -> Optional[str]:
    """chromedriver 경로 해석. Selenium Manager(외부 다운로드)는 절대 사용 안 함.
    우선순위: CHROMEDRIVER_PATH → 알려진 후보 경로 → PATH."""
    env_path = os.environ.get("CHROMEDRIVER_PATH", "").strip()
    if _is_executable_file(env_path):
        return env_path
    for c in _CANDIDATE_CHROMEDRIVER_PATHS:
        if _is_executable_file(c):
            return c
    found = shutil.which("chromedriver")
    if found and _is_executable_file(found):
        return found
    return None


def _resolve_chrome_binary() -> Optional[str]:
    """chrome/chromium 바이너리 경로 해석 (표준 위치면 None 반환해도 무방)."""
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


def _build_driver() -> webdriver.Chrome:
    """
    Rocky 8.9 사내 미러 + chromium/chrome RPM + chromedriver tarball 가정.
    headless 기본, --no-sandbox 폐쇄망 컨테이너 호환.

    v19.18 — driver 를 _resolve_chromedriver() 로 직접 해석 후 Service 로만 기동.
    Selenium Manager(외부 다운로드)로 절대 fallback 하지 않음. 못 찾으면 명확히 실패.
    """
    options = Options()
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1920,1080")
    options.add_argument("--disable-gpu")

    chrome_bin = _resolve_chrome_binary()
    if chrome_bin:
        options.binary_location = chrome_bin

    driver_path = _resolve_chromedriver()
    if not driver_path:
        raise RuntimeError(
            "chromedriver 를 찾을 수 없습니다. 폐쇄망에서는 Selenium Manager 의 "
            "외부 자동 다운로드가 불가합니다. CHROMEDRIVER_PATH 환경변수, "
            "app.scripts.chromedriver-path 설정, 또는 아래 후보 경로 중 하나에 "
            "사내 미러 chromedriver 를 배치하세요. 확인한 후보: "
            + ", ".join(_CANDIDATE_CHROMEDRIVER_PATHS)
        )

    print(f"[wrapper] chromedriver 사용: {driver_path}"
          + (f" / chrome: {chrome_bin}" if chrome_bin else ""), file=sys.stderr)

    service = Service(executable_path=driver_path)
    return webdriver.Chrome(service=service, options=options)


def _write_driver_init_failure(
    *, output_dir: Path,
    exc: Exception,
    scenario_name: str,
    defined_labels: list[str],
) -> None:
    """
    v18.8.5 — chrome driver 초기화 실패 시 진단 JSON 작성.

    driver 가 없으므로 스크린샷 / page_source 캡쳐 불가. 단계 1 = "chrome driver 초기화" 실패,
    이후 단계들 = 모두 not_run (AST 로 추출된 label 그대로).
    """
    now = datetime.now(KST)
    exception_class = type(exc).__name__
    korean_message = KOREAN_ERROR_MAP.get(exception_class, DEFAULT_KOREAN_ERROR)
    primary_cause = PRIMARY_CAUSE_MAP.get(exception_class, DEFAULT_PRIMARY_CAUSE)

    # primary_cause 의 {target} placeholder 안전 처리 (driver init 시점에는 target 없음)
    try:
        primary_cause = primary_cause.format(target="(driver 초기화)")
    except (KeyError, IndexError):
        # format 실패 시 원본 그대로
        pass

    # 1번째 step = driver init 실패
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

    # 나머지 = scenario 정의 단계들 (모두 not_run)
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
        "scenario": {
            "name": scenario_name,
            "total_steps": len(steps),
        },
        "steps": steps,
        "diagnosis": {
            "primary_cause":     primary_cause,
            "screenshot_path":   None,   # driver 없어서 스크린샷 불가
            "page_source_path":  None,   # driver 없어서 page_source 불가
        },
    }

    output_path = output_dir / DIAGNOSIS_FILENAME
    try:
        output_path.write_text(
            json.dumps(diagnosis, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"[wrapper] driver 초기화 실패 — 진단 JSON 저장: {output_path}", file=sys.stderr)
    except Exception as write_exc:
        print(f"[wrapper] driver 초기화 실패 진단 JSON 쓰기도 실패: {write_exc}", file=sys.stderr)


def _write_diagnosis_json(
    ctx: _ExecutionContext, *,
    status: str,
    scenario_name: str,
    error: Optional[Exception],
) -> None:
    """_diagnosis.json 산출. ScriptExecutionService.collectOutputFiles() 가 본 파일 감지 + JobExecution.errorDiagnosis 저장."""

    ended_at = datetime.now(KST)
    total_duration = round((ended_at - ctx.started_at).total_seconds(), 2)

    # v18.8.5 — 정의된 라벨 중 실행 안 된 것들 = not_run 자동 채움
    # AST 추출 실패 (defined_labels 가 빈 리스트) 시 graceful fallback — not_run 추가 안 함
    if status == "failed" and ctx.defined_labels:
        already_recorded = len(ctx.steps)
        defined_count = len(ctx.defined_labels)
        if defined_count > already_recorded:
            for i in range(already_recorded, defined_count):
                label = ctx.defined_labels[i]
                selenium_cmd, target = _split_label(label)
                ctx.steps.append({
                    "order": i + 1,
                    "label": label,
                    "selenium_cmd": selenium_cmd,
                    "target": target,
                    "status": "not_run",
                })

    # v18.8.5 — total_steps = 정의된 총 단계 수 (AST 결과 우선, 없으면 실행된 단계 수 fallback)
    total_steps = max(len(ctx.defined_labels), len(ctx.steps))

    # primary_cause 자동 추론 — 실패 단계의 exception_class 매핑
    primary_cause = None
    if status == "failed":
        failed_step = next((s for s in ctx.steps if s["status"] == "failed"), None)
        if failed_step and "error" in failed_step:
            exc_class = failed_step["error"]["exception_class"]
            target = failed_step["error"].get("selector", "")
            template = PRIMARY_CAUSE_MAP.get(exc_class, DEFAULT_PRIMARY_CAUSE)
            try:
                primary_cause = template.format(target=target or "(target 정보 없음)")
            except (KeyError, IndexError):
                primary_cause = template

    diagnosis = {
        "schema_version": "1.0",
        "execution": {
            "started_at":   ctx.started_at.isoformat(),
            "ended_at":     ended_at.isoformat(),
            "duration_sec": total_duration,
            "status":       status,
        },
        "scenario": {
            "name": scenario_name,
            "total_steps": total_steps,
        },
        "steps": ctx.steps,
        "diagnosis": {
            "primary_cause":     primary_cause,
            "screenshot_path":   SCREENSHOT_FILENAME if status == "failed" else None,
            "page_source_path":  PAGE_SOURCE_FILENAME if status == "failed" else None,
        },
    }

    output_path = ctx.output_dir / DIAGNOSIS_FILENAME
    output_path.write_text(
        json.dumps(diagnosis, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"[wrapper] 진단 JSON 저장: {output_path}", file=sys.stderr)


# ──────────────────────────────────────────────────────────────────────
# 관리자 작성 예시 (본 파일 직접 실행 시 빠른 검증)
# 실제 운영 스크립트는 본 wrapper 를 import 후 자기 scenario 작성.
# ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    # 예시 — 실제 운영에서는 본 블록 대신 관리자가 별도 .py 파일 작성
    from selenium.webdriver.common.by import By

    def example_scenario(driver, output_dir):
        """정보자산 목록 수집 (사용자 mockup 정합 7 단계)."""
        with step("open https://example.com/login"):
            driver.get("https://example.com/login")

        with step("type id=user-id"):
            driver.find_element(By.ID, "user-id").send_keys("admin")

        with step("type id=user-pw"):
            driver.find_element(By.ID, "user-pw").send_keys("password")

        with step("click .login-btn"):
            driver.find_element(By.CSS_SELECTOR, ".login-btn").click()

        with step("click button.login-submit"):
            driver.find_element(By.CSS_SELECTOR, "button.login-submit").click()

        with step("open /assets/list"):
            driver.get(driver.current_url.rstrip("/") + "/assets/list")

        with step("storeText table#assets-table"):
            text = driver.find_element(By.CSS_SELECTOR, "table#assets-table").text
            output_path = output_dir / "assets.txt"
            output_path.write_text(text, encoding="utf-8")

    exit_code = execute_with_diagnosis(example_scenario)
    sys.exit(exit_code)