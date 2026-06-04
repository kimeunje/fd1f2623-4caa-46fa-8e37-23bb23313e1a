"""
SecuHub Collection Task — Template Method 진입점.

v18.9.12 신규. 사용자는 main(ctx) 함수에 @collect_task 적용만 — 프레임워크가
driver 생성/옵션/저장 경로/진단/cleanup 전부 처리. Scrapy 류의 선언적 + 함수 기반.

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
"""
import functools
import json
import os
import sys
import time
import traceback
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Optional


# ============================================================================
# Context — 사용자 main(ctx) 에 주입되는 컨텍스트 객체
# ============================================================================
class _Context:
    """
    main(ctx) 안에서 사용. 사용자는 직접 인스턴스화하지 않음.
    """

    def __init__(self, output: Path, env: dict, driver=None):
        self.driver = driver       # webdriver | None
        self.output = output       # Path — 결과 저장 디렉토리
        self.env = env             # job_id, execution_id, etc.

        # 진단 누적 (step 진입/종료 기록)
        self._diagnostics: list[dict] = []
        self._current_step: Optional[str] = None

    @contextmanager
    def step(self, label: str):
        """
        단계별 진단 context manager.

            with ctx.step("로그인"):
                ctx.driver.get("...")

        시작/종료 시각, 예외 발생 여부를 진단에 기록. cleanup 시점에
        ctx.output/_diagnosis.json 으로 flush.
        """
        entry = {
            "label": label,
            "started_at": time.time(),
            "status": "running",
        }
        self._diagnostics.append(entry)
        self._current_step = label
        try:
            yield
            entry["status"] = "success"
        except Exception as e:
            entry["status"] = "failed"
            entry["error"] = repr(e)
            entry["traceback"] = traceback.format_exc()
            raise
        finally:
            entry["ended_at"] = time.time()
            entry["duration_ms"] = int((entry["ended_at"] - entry["started_at"]) * 1000)
            self._current_step = None

    def _flush_diagnostics(self, overall_status: str, error: Optional[str] = None):
        """cleanup 시 진단 JSON 산출. BE 가 outputDir/_diagnosis.json 자동 수집."""
        payload = {
            "version": "v18.9.12",
            "status": overall_status,
            "error": error,
            "current_step_when_failed": self._current_step if overall_status == "failed" else None,
            "steps": self._diagnostics,
            "env": self.env,
        }
        try:
            (self.output / "_diagnosis.json").write_text(
                json.dumps(payload, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        except Exception as e:
            # 진단 산출 실패는 silent — 본 흐름에 영향 없음
            print(f"[secuhub_task] _diagnosis.json 산출 실패: {e}", file=sys.stderr)


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

    # ── 폐쇄망 — 드라이버/브라우저 경로 명시 (selenium manager 자동 다운로드 불가) ──
    # 운영 서버는 인터넷이 없어 selenium 4.x 의 드라이버 자동 탐색/다운로드가 막힘.
    # 사전 배치한 chromedriver 경로를 직접 지정. 경로는 환경변수로 빼서 위치/버전
    # 변경 시 코드 수정 불필요 (미지정 시 기본 /var/lib/secuhub/drivers/chromedriver).
    chromedriver_path = os.environ.get(
        "SECUHUB_CHROMEDRIVER", "/var/lib/secuhub/drivers/chromedriver"
    )
    # 크롬 본체 경로. 표준 위치(/usr/bin/google-chrome)면 보통 자동 인식되어 생략 가능.
    # 비표준 위치일 때만 SECUHUB_CHROME_BINARY 로 지정.
    chrome_binary = os.environ.get("SECUHUB_CHROME_BINARY")
    if chrome_binary:
        opts.binary_location = chrome_binary

    service = Service(executable_path=chromedriver_path)
    driver = webdriver.Chrome(service=service, options=opts)
    return driver


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

        # ── driver 생성 (use_browser 분기) ──
        driver = None
        if use_browser:
            try:
                driver = _build_driver(headless, chrome_options, chrome_prefs, output_dir)
            except Exception as e:
                # driver init 실패도 진단으로 기록 (v18.8.5 흐름 정합)
                ctx_fail = _Context(output_dir, env)
                ctx_fail._flush_diagnostics("driver_init_failed", error=repr(e))
                print(f"[secuhub_task] driver 초기화 실패: {e}", file=sys.stderr)
                traceback.print_exc()
                sys.exit(1)

        # ── ctx 구성 + 사용자 main(ctx) 호출 ──
        ctx = _Context(output_dir, env, driver=driver)
        overall_status = "success"
        error_repr: Optional[str] = None
        try:
            fn(ctx)
        except SystemExit:
            raise
        except Exception as e:
            overall_status = "failed"
            error_repr = repr(e)
            print(f"[secuhub_task] main 실패: {e}", file=sys.stderr)
            traceback.print_exc()
        finally:
            # 진단 flush
            ctx._flush_diagnostics(overall_status, error=error_repr)
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