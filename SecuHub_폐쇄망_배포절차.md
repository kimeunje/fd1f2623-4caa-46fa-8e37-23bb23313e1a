# SecuHub 자동 수집 — 폐쇄망 배포 절차

> 운영 서버(폐쇄망, Rocky Linux 8.9 / x86_64)에서 자동 수집(selenium 기반) 기능을
> 동작시키기 위한 설치·설정 절차. 서버 재구축 또는 2호기 구축 시 본 문서를 그대로 따른다.
>
> **전제 환경**: Rocky Linux 8.9, x86_64, 인터넷 차단(폐쇄망).
> 외부 다운로드는 **인터넷이 되는 동일 환경(Rocky 8.9 / x86_64) PC**에서 수행한다.
>
> **핵심 원칙 3가지**
> 1. 모든 파이썬 작업은 `python3.11` 로 한다 (`python3` 가 옛 버전을 가리킬 수 있음).
> 2. 외부에서 받는 wheel·RPM은 **서버와 동일한 OS·아키텍처·파이썬 버전**이어야 한다.
> 3. **크롬 메이저 버전 = 크롬드라이버 메이저 버전** 이 반드시 일치해야 한다.

---

## 0. 설치 대상 한눈에 보기

| 구분 | 항목 | 설치 방식 | 비고 |
|------|------|-----------|------|
| 런타임 | python3.11 + pip | dnf / ensurepip | pip은 별도 패키지일 수 있음 |
| 파이썬 라이브러리 | selenium | wheel 오프라인 설치 | python3.11 에 설치 |
| 브라우저 | google-chrome | RPM(의존성 포함) | 버전 기록 필수 |
| 드라이버 | chromedriver | 바이너리 배치 | 크롬과 메이저 일치 |
| 폰트 | 한글 폰트(nanum 등) | RPM | 캡처 증빙에 한글 시 필요 |
| 설정 | application-prod.yml | 형상관리 | python-command 지정 |
| 설정 | 환경변수 | 서버 환경 | 드라이버 경로 등 |

---

## 1. python3.11 + pip

### 1-1. python3.11 설치
운영 서버에 python3.11이 없으면 외부에서 RPM을 받아 설치한다.
- 외부(인터넷) PC: `sudo dnf install python3.11` 로 설치되면, 동일 RPM을 받아 서버로 이관.
  - RPM만 받기: `dnf download --resolve --alldeps --destdir ~/py311_rpms python3.11`
- 서버: 이관 후 `dnf install ./*.rpm`

확인:
```
python3.11 --version
```

### 1-2. pip 확보
`python3.11 -m pip --version` 이 `No module named pip` 이면 pip이 없는 것.
폐쇄망에서는 인터넷이 필요 없는 **ensurepip** 로 부트스트랩한다.
```
python3.11 -m ensurepip --upgrade
python3.11 -m pip --version
```

> ⚠ `dnf install python3.11-pip` 는 인터넷이 필요하므로 폐쇄망 서버에서는 보통 실패한다.
> ensurepip 가 폐쇄망에서 가장 확실하다.

---

## 2. selenium (wheel 오프라인 설치)

### 2-1. 외부 PC에서 wheel 다운로드 (의존성 포함)
인터넷 되는 Rocky 8.9 / python3.11 PC에서:
```
python3.11 -m pip download selenium -d ./selenium_pkgs
```
`selenium_pkgs` 안에 selenium + 의존성(urllib3, trio, certifi 등) wheel이 함께 받아졌는지 확인.
selenium 단독만 있으면 의존성 누락 — 동일 환경에서 다시 받을 것.

### 2-2. 서버로 이관 후 설치
`selenium_pkgs` 폴더를 서버로 옮긴 뒤, **반드시 python3.11 에**:
```
python3.11 -m pip install --no-index --find-links ./selenium_pkgs selenium
```
- `--no-index`: 인터넷 보지 않음
- `--find-links`: 이 폴더 안 파일로만 설치

확인:
```
python3.11 -c "import selenium; print(selenium.__version__)"
```

---

## 3. 구글 크롬 (RPM + 의존성)

### 3-1. 외부 PC에서 RPM 다운로드 (의존성 포함)
인터넷 되는 Rocky 8.9 PC에서 구글 크롬 저장소를 등록한다.
`/etc/yum.repos.d/google-chrome.repo`:
```
[google-chrome]
name=google-chrome
baseurl=https://dl.google.com/linux/chrome/rpm/stable/x86_64
enabled=1
gpgcheck=1
gpgkey=https://dl.google.com/linux/linux_signing_key.pub
```
크롬 + 의존성 RPM을 한 폴더에 받는다(설치 아님, 다운로드만):
```
mkdir -p ~/chrome_rpms
dnf download --resolve --alldeps --destdir ~/chrome_rpms google-chrome-stable
```

### 3-2. 서버로 이관 후 설치
`chrome_rpms` 폴더를 서버로 옮긴 뒤 폴더 안에서:
```
dnf install ./*.rpm
```

### 3-3. 버전 확인 (★ 기록 필수)
```
google-chrome --version
```
여기서 나온 **메이저 버전**을 기록한다. 이 값이 4절(드라이버) 버전을 결정한다.
- 예: `Google Chrome 149.0.7827.53` → 메이저 = **149**

> 폐쇄망 크롬은 자동 보안 업데이트가 안 된다. 주기적으로 동일 절차로 갱신할 것.

---

## 4. 크롬드라이버 (크롬과 메이저 일치)

### 4-1. 외부 PC에서 버전 매핑 확인
크롬 115+ 는 "Chrome for Testing" 채널에서 드라이버를 받는다.
인터넷 PC 브라우저에서 아래 JSON을 열고, 3-3에서 기록한 메이저(예: 149)를 검색:
```
https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json
```
`downloads.chromedriver` 에서 `platform: "linux64"` URL을 확인한다.
드라이버는 **메이저만 맞으면** 정상 동작(예: 크롬 149 → 드라이버 149.x).

### 4-2. 다운로드 → 서버 배치
linux64 chromedriver(zip)를 받아 압축 해제 후, 서버 고정 위치에 배치:
```
mkdir -p /var/lib/secuhub/drivers
mv chromedriver /var/lib/secuhub/drivers/chromedriver
chmod +x /var/lib/secuhub/drivers/chromedriver
```

### 4-3. 버전 일치 확인
```
/var/lib/secuhub/drivers/chromedriver --version
```
`ChromeDriver 149.0.x` 처럼 크롬과 메이저가 같아야 한다.

> 드라이버 실행 시 `error while loading shared libraries` 가 나면 폐쇄망에 라이브러리 누락.
> 부족한 .so를 제공하는 RPM을 외부에서 받아 설치한다.

---

## 5. 한글 폰트 (캡처 증빙용)

캡처(스크린샷) 방식 수집에서 페이지에 한글이 있으면, 폰트가 없을 때 □□(두부)로 깨진다.
다운로드 방식만 쓰면 불필요하나, 캡처를 쓴다면 필수.
외부에서 nanum 등 한글 폰트 RPM을 받아 설치:
```
# 외부 PC
dnf download --resolve --alldeps --destdir ~/font_rpms google-noto-sans-cjk-ttc-fonts
# 또는 nanum 계열
# 서버
dnf install ./*.rpm
fc-cache -f
```

---

## 6. SecuHub 설정 (형상관리 + 서버 환경)

### 6-1. application-prod.yml (★ 형상관리 포함)
`.py` 실행에 쓰는 파이썬 명령을 python3.11 로 지정. **누락 시 python3(옛 버전)로 돌아 동일 에러 재발.**
```yaml
app:
  scripts:
    base-dir: /var/lib/secuhub/scripts
    python-command: python3.11        # 필수
  storage:
    path: /var/lib/secuhub/storage     # 저장소 표준 경로
```

### 6-2. 환경변수 (서버 환경 — 형상관리 불가)
크롬드라이버 경로를 SecuHub 실행 프로세스에 주입한다. 기본값(`/var/lib/secuhub/drivers/chromedriver`)
에 배치했다면 생략 가능하나, 명시를 권장.
```
SECUHUB_CHROMEDRIVER=/var/lib/secuhub/drivers/chromedriver
# 크롬이 비표준 위치일 때만:
# SECUHUB_CHROME_BINARY=/usr/bin/google-chrome
```
> systemd 서비스로 구동한다면 unit 파일의 `Environment=` 또는 `EnvironmentFile=` 에 등록.

### 6-3. secuhub_task.py 배포 위치
사용자 스크립트가 `from secuhub_task import collect_task` 로 찾을 수 있어야 한다.
형상관리의 최신 `secuhub_task.py` 가 `scripts/templates/`(또는 실행 시 PYTHONPATH에 잡히는 위치)에
배포됐는지 확인. **손으로 복사한 경우 재배포 시 사라지므로, 정식 배포 산출물에 포함시킬 것.**

---

## 7. 동작 검증 (설치 완료 후)

### 7-1. 구성요소 개별 확인
```
python3.11 --version
python3.11 -m pip --version
python3.11 -c "import selenium; print(selenium.__version__)"
google-chrome --version
/var/lib/secuhub/drivers/chromedriver --version    # 크롬과 메이저 일치 확인
```

### 7-2. 최소 스크립트로 end-to-end 확인
SecuHub 화면에서 간단한 `@collect_task` 스크립트를 등록·실행한다.
- `use_browser=False` + 파일 1개 저장 → 등록·실행·수집·진단 루프 확인
- `use_browser=True` + 사내 시스템 접속 → 크롬·드라이버·DNS까지 확인

### 7-3. 단계별 실패 시 원인 매핑
| 증상 | 원인 | 조치 |
|------|------|------|
| `No module named 'selenium'` | selenium이 python3.11 외 다른 파이썬에 설치 | python3.11 에 재설치(2절) |
| `'type' object is not subscriptable` | python3 옛 버전으로 실행 중 | application-prod.yml python-command 확인(6-1) |
| `No module named 'secuhub_task'` | 템플릿 미배포 / 경로 불일치 | 배포 위치 확인(6-3) |
| 0.2초 exit 1, 진단 공백 | 본문 진입 전 사망(import 단계) | 실행 에러 메시지/서버 로그 stderr 확인 |
| `unable to obtain chromedriver` | 드라이버 경로 불일치 | 배치·권한·SECUHUB_CHROMEDRIVER 확인(4,6-2) |
| `cannot find Chrome binary` | 크롬 본체 못 찾음 | SECUHUB_CHROME_BINARY 지정(6-2) |
| `session not created ... only supports Chrome version` | 크롬↔드라이버 메이저 불일치 | 드라이버 버전 재확인(4) |
| `net::ERR_NAME_NOT_RESOLVED` | DNS 미해석(폐쇄망/오타/사내주소) | nslookup·ping, DNS 또는 /etc/hosts 등록 |
| 캡처 한글 □□ | 한글 폰트 없음 | 한글 폰트 설치(5절) |

---

## 8. 사용자 스크립트 작성 규칙 (요약)

운영자가 사용자에게 안내할 핵심 규칙.

- `@collect_task` 틀을 사용한다(드라이버·경로·진단·정리 자동 처리).
- 드라이버를 직접 만들지 않고 `ctx.driver` 사용. `webdriver.Chrome(...)` 직접 호출 금지.
- 저장은 `ctx.output` 또는 상대경로. **절대경로(`/tmp`, `C:\`) 금지** — 수집 폴더 밖이라 누락/깨짐.
- 사이트별 옵션(예: `--ignore-certificate-errors`)은 `chrome_options=[...]` 에 명시
  (한 번만, 여러 개면 한 리스트에). 서버 필수 옵션(headless/no-sandbox 등)은 자동.
- 다운로드 방식: 클릭 후 완료 대기(`time.sleep`) 필요(비동기).
- 캡처 방식: `driver.save_screenshot(str(ctx.output / "이름.png"))`. 동기라 대기 불필요.
  캡처 잘림 방지 위해 `chrome_options=["--window-size=1920,1080"]` 권장.

---

## 부록 — 버전 기록표 (구축 시마다 갱신)

| 항목 | 버전 | 구축일 | 비고 |
|------|------|--------|------|
| Rocky Linux | 8.9 | | |
| python3.11 | | | |
| selenium | | | |
| google-chrome | | | 메이저 = 드라이버 기준 |
| chromedriver | | | 크롬과 메이저 일치 |
| 한글 폰트 | | | 캡처 사용 시 |
