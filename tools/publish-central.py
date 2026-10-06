#!/usr/bin/env python3
"""AIAvatar-SDK → Maven Central（Central Portal）上传脚本（README Roadmap ①）。

流程（约定：产物先用 `./gradlew publishToMavenLocal` 落到 ~/.m2/repository）：
  1. 取 ~/.m2/repository/io/github/hufeiya/{corelib,avatar-ai-adapter,avatar-orchestrator}/<ver>
  2. 每个目录补齐 Central 要求的附属文件：
     - 所有文件生成 .sha1 / .md5
     - 主产物 / .pom / -sources / .module 生成 .asc 签名（gpg CLI 分离签名）
     - 缺失时生成 -javadoc.jar 存根（index.html——Central 强制要求 javadoc 产物存在）
  3. 打 bundle.zip（内部目录结构 = 仓库相对路径 io/github/hufeiya/...）
  4. POST 上传 Central Portal、轮询部署状态到 PUBLISHED / FAILED。

用法：
  python3 tools/publish-central.py                # 打包+上传+轮询
  python3 tools/publish-central.py --bundle-only  # 只打包（检查 zip 内容用）

凭据（Central Portal 用户令牌 + GPG 口令）从环境变量读，不落仓库：
  CENTRAL_TOKEN_USER / CENTRAL_TOKEN_PASS / GPG_PASSPHRASE（可选，缺省交互提示）

⚠ 网络：release.central.sonatype.com 在本机代理下 TLS 被断（2026-10-07 实测），
  同源的 central.sonatype.com/api/v1/publisher/* 可用——上传/轮询都走它。
"""
import glob
import os
import subprocess
import sys
import time
import zipfile
from pathlib import Path

GROUP = "io.github.hufeiya"
GROUP_PATH = GROUP.replace(".", "/")
ARTIFACTS = ["corelib", "avatar-ai-adapter", "avatar-orchestrator"]
BASE = Path.home() / ".m2" / "repository" / GROUP_PATH
API = "https://central.sonatype.com/api/v1/publisher"
KEY_ID = "F7AE0F97DF1A2BFD"

M2_ROOT = Path.home() / ".m2" / "repository"
WORK = Path("/tmp/central-bundle")


def gpg(*args: str) -> None:
    env = dict(os.environ)
    passphrase = env.get("GPG_PASSPHRASE", "REDACTED")
    subprocess.run(
        ["gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
         "--passphrase", passphrase, "--local-user", KEY_ID, *args],
        check=True,
    )


def sha1(data: bytes) -> str:
    import hashlib
    return hashlib.sha1(data).hexdigest()


def md5(data: bytes) -> str:
    import hashlib
    return hashlib.md5(data).hexdigest()


def make_javadoc_stub(target: Path, project: str, version: str) -> None:
    """Central 强制 -javadoc.jar 存在；Kotlin 工程没有 javadoc 任务，放最小存根。"""
    jar = target / f"{project}-{version}-javadoc.jar"
    if jar.exists():
        return
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr("index.html", (
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            f"<title>{project} {version}</title></head><body>"
            f"<h1>{project} {version}</h1>"
            "<p>Kotlin SDK — API documentation is generated with Dokka in the source "
            "repository (KDoc is embedded in the sources JAR).</p>"
            "</body></html>"
        ))


def main() -> None:
    bundle_only = "--bundle-only" in sys.argv
    version = None
    for a in ARTIFACTS:
        dirs = sorted(p for p in BASE.glob(a + "/*") if p.is_dir())
        if not dirs:
            sys.exit(f"missing artifacts for {a} — run ./gradlew publishToMavenLocal first")
        v = dirs[-1].name
        if version and version != v:
            sys.exit(f"version mismatch across artifacts: {v} vs {version}")
        version = v
    print(f"== version {version}, group {GROUP}")

    if WORK.exists():
        import shutil
        shutil.rmtree(WORK)
    repo = WORK / GROUP_PATH
    for a in ARTIFACTS:
        src = BASE / a / version
        dst = repo / a / version
        dst.mkdir(parents=True)
        for f in sorted(src.iterdir()):
            if f.name == "maven-metadata-local.xml" or "local" in f.name:
                continue  # 本地标记文件不入库
            data = f.read_bytes()
            (dst / f.name).write_bytes(data)
            (dst / (f.name + ".sha1")).write_text(sha1(data))
            (dst / (f.name + ".md5")).write_text(md5(data))
        make_javadoc_stub(dst, a, version)
        stub = dst / f"{a}-{version}-javadoc.jar"
        stub_bytes = stub.read_bytes()
        (dst / (stub.name + ".sha1")).write_text(sha1(stub_bytes))
        (dst / (stub.name + ".md5")).write_text(md5(stub_bytes))
        # 分离签名：主产物 + pom + sources + javadoc + module（javadoc 刚生成，一并签）
        for f in sorted(dst.iterdir()):
            if f.name.endswith((".sha1", ".md5", ".asc")):
                continue
            sig = Path(str(f) + ".asc")
            gpg("--armor", "--detach-sign", "--output", str(sig), str(f))
        print(f"== staged {a}: {len(list(dst.iterdir()))} files")

    bundle = WORK.parent / "bundle.zip"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(repo.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(WORK))
    print(f"== bundle: {bundle} ({bundle.stat().st_size / 1e6:.1f} MB, "
          f"{len(list(repo.rglob('*'))) - len([p for p in repo.rglob('*') if p.is_dir()])} files)")
    if bundle_only:
        return

    user = os.environ.get("CENTRAL_TOKEN_USER", "")
    pwd = os.environ.get("CENTRAL_TOKEN_PASS", "")
    if not (user and pwd):
        sys.exit("set CENTRAL_TOKEN_USER / CENTRAL_TOKEN_PASS (Central Portal user token)")
    import base64
    import urllib.request
    import urllib.error

    # Portal API 的令牌格式：Bearer + base64("user:pass")（2026-10-07 实测：
    # 原始 "Bearer user:pass" 报 Invalid token，base64 编码后通过）
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()

    boundary = "----aiavatarbundle"
    payload = bundle.read_bytes()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="bundle"; filename="bundle.zip"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + payload + f"\r\n--{boundary}--\r\n".encode()
    url = (f"{API}/upload?name=AIAvatar-SDK-{version}&publishingType=AUTOMATIC")
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            deployment_id = resp.read().decode().strip()
    except urllib.error.HTTPError as e:
        sys.exit(f"upload failed: HTTP {e.code}\n{e.read().decode()[:2000]}")
    print(f"== uploaded, deployment {deployment_id}")

    while True:
        time.sleep(15)
        req = urllib.request.Request(
            f"{API}/status?id={deployment_id}",
            headers={"Authorization": f"Bearer {token}"},
        )
        with urllib.request.urlopen(req, timeout=60) as r:
            import json
            status = json.loads(r.read())
        state = status.get("deploymentState")
        print(f"   state={state}")
        if state == "PUBLISHED":
            print("== PUBLISHED 🎉  https://central.sonatype.com/artifact/io.github.hufeiya/corelib")
            return
        if state == "FAILED":
            print(status)
            sys.exit("== validation/publish FAILED（见上方 errors）")
        if state not in ("PENDING_VALIDATION", "VALIDATING", "VALIDATED", "PUBLISHING"):
            print(status)
            sys.exit(f"== unexpected state {state}")


if __name__ == "__main__":
    main()
