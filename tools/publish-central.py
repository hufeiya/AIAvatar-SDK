#!/usr/bin/env python3
"""AIAvatar-SDK → Maven Central（Central Portal）发布脚本（README Roadmap ①）。

日常发布不要直接用本脚本——用一键入口 `tools/release.sh <版本号>`（改版本号、
跑测试、提交、发布、盯发布确认一条龙）。本脚本只负责发布链路的四个子命令：

  bundle <version>   从 mavenLocal（~/.m2）打 bundle.zip（须先 publishToMavenLocal）
  upload <version>   上传 bundle 到 Central Portal，打印 deployment id
  watch <version>    轮询 repo1.maven.org 直到三产物可下载（发布成功的最终信号）
  full <version>     = bundle + upload + watch
  status <deployId>  查询 deployment 状态（release 域可达时才有意义，本机代理下
                     通常 500/断连——日常确认发布一律用 watch）

凭据解析顺序：环境变量 CENTRAL_TOKEN_USER / CENTRAL_TOKEN_PASS / GPG_PASSPHRASE →
仓库根目录 `central.properties`（gitignored：centralTokenUser / centralTokenPass /
gpgPassphrase / gpgKeyId）。找不到即报错退出。

⚠ 网络事实（2026-10-07）：release.central.sonatype.com 在本机代理下 TLS 被断；
  同源 central.sonatype.com 的 /api/v1/publisher/upload 可用（upload 走它），
  但该域的 status 接口恒 500——所以发布确认看 repo1（watch）。
"""
import base64
import hashlib
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

GROUP = "io.github.hufeiya"
GROUP_PATH = GROUP.replace(".", "/")
ARTIFACTS = ["corelib", "avatar-ai-adapter", "avatar-orchestrator"]
REPO1 = "https://repo1.maven.org/maven2"
UPLOAD_HOST = "https://central.sonatype.com"
RELEASE_HOST = "https://release.central.sonatype.com"
WORK = Path("/tmp/central-bundle")
KEY_ID = "F7AE0F97DF1A2BFD"  # 公钥指纹可公开；口令在凭据文件里

M2_ROOT = Path.home() / ".m2" / "repository"
REPO_ROOT = Path(__file__).resolve().parent.parent


def die(msg: str) -> None:
    print(f"✗ {msg}", file=sys.stderr)
    sys.exit(1)


def load_props() -> dict:
    """环境变量优先，其次仓库根 central.properties（gitignored）。"""
    props = {}
    p = REPO_ROOT / "central.properties"
    if p.exists():
        for line in p.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return {
        "token_user": os.environ.get("CENTRAL_TOKEN_USER") or props.get("centralTokenUser"),
        "token_pass": os.environ.get("CENTRAL_TOKEN_PASS") or props.get("centralTokenPass"),
        "gpg_pass": os.environ.get("GPG_PASSPHRASE") or props.get("gpgPassphrase"),
    }


def need_creds() -> dict:
    c = load_props()
    if not (c["token_user"] and c["token_pass"]):
        die("缺少 Central Portal 令牌：设 CENTRAL_TOKEN_USER/PASS 环境变量，"
            "或在仓库根 central.properties 写 centralTokenUser/centralTokenPass（已 gitignore）")
    if not c["gpg_pass"]:
        die("缺少 GPG 口令：设 GPG_PASSPHRASE 环境变量，或在 central.properties 写 gpgPassphrase")
    return c


def sha(data: bytes, algo: str) -> str:
    return hashlib.sha1(data).hexdigest() if algo == "sha1" else hashlib.md5(data).hexdigest()


def gpg_sign_file(src: Path, dst: Path, c: dict) -> None:
    subprocess.run(
        ["gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
         "--passphrase", c["gpg_pass"], "--local-user", KEY_ID,
         "--armor", "--detach-sign", "--output", str(dst), str(src)],
        check=True,
    )


def cmd_bundle(version: str) -> Path:
    bundle = Path(f"/tmp/bundle-{version}.zip")
    if WORK.exists():
        import shutil
        shutil.rmtree(WORK)
    repo = WORK / GROUP_PATH
    c = need_creds()
    for a in ARTIFACTS:
        src = M2_ROOT / GROUP_PATH / a / version
        if not src.is_dir():
            die(f"mavenLocal 缺 {a}:{version} —— 先跑 ./gradlew publishToMavenLocal")
        dst = repo / a / version
        dst.mkdir(parents=True)
        for f in sorted(src.iterdir()):
            if "local" in f.name:  # maven-metadata-local.xml 等
                continue
            data = f.read_bytes()
            (dst / f.name).write_bytes(data)
            (dst / (f.name + ".sha1")).write_text(sha(data, "sha1"))
            (dst / (f.name + ".md5")).write_text(sha(data, "md5"))
        # javadoc 存根（Central 强制 javadoc 产物存在；Kotlin 工程无 javadoc 任务）
        jar = dst / f"{a}-{version}-javadoc.jar"
        if not jar.exists():
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("index.html", (
                    "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    f"<title>{a} {version}</title></head><body>"
                    f"<h1>{a} {version}</h1>"
                    "<p>Kotlin SDK — API documentation is generated with Dokka in the "
                    "source repository (KDoc is embedded in the sources JAR).</p>"
                    "</body></html>"))
        jdata = jar.read_bytes()
        (dst / (jar.name + ".sha1")).write_text(sha(jdata, "sha1"))
        (dst / (jar.name + ".md5")).write_text(sha(jdata, "md5"))
        for f in sorted(dst.iterdir()):
            if f.name.endswith((".sha1", ".md5", ".asc")):
                continue
            gpg_sign_file(f, Path(str(f) + ".asc"), c)
        print(f"== staged {a}: {len(list(dst.iterdir()))} files")

    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(repo.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(WORK))
    n = len(list(repo.rglob("*"))) - len([p for p in repo.rglob("*") if p.is_dir()])
    print(f"== bundle: {bundle} ({bundle.stat().st_size / 1e6:.1f} MB, {n} files)")
    return bundle


def cmd_upload(version: str) -> str:
    c = need_creds()
    bundle = Path(f"/tmp/bundle-{version}.zip")
    if not bundle.exists():
        die(f"缺 {bundle} —— 先跑 bundle {version}")
    token = base64.b64encode(f"{c['token_user']}:{c['token_pass']}".encode()).decode()
    boundary = "----aiavatarbundle"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="bundle"; filename="bundle.zip"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + bundle.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        f"{UPLOAD_HOST}/api/v1/publisher/upload?name=AIAvatar-SDK-{version}&publishingType=AUTOMATIC",
        data=body, method="POST",
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            deployment_id = resp.read().decode().strip()
    except urllib.error.HTTPError as e:
        die(f"upload failed: HTTP {e.code}\n{e.read().decode()[:2000]}")
    print(f"== uploaded, deployment {deployment_id} (publishingType=AUTOMATIC)")
    return deployment_id


def cmd_status(deployment_id: str) -> None:
    token = base64.b64encode(
        f"{need_creds()['token_user']}:{need_creds()['token_pass']}".encode()).decode()
    for host in (RELEASE_HOST, UPLOAD_HOST):
        try:
            req = urllib.request.Request(
                f"{host}/api/v1/publisher/status?id={deployment_id}",
                headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(req, timeout=30) as r:
                print(f"[{host}] {r.read().decode()[:800]}")
            return
        except Exception as e:
            print(f"[{host}] 不可达: {e}")
    sys.exit(1)


def cmd_watch(version: str, timeout_min: int) -> None:
    """发布成功的最终信号 = repo1 上三产物可下载（status 接口在本机不可靠）。"""
    urls = [f"{REPO1}/{GROUP_PATH}/{a}/{version}/{a}-{version}.pom" for a in ARTIFACTS]
    deadline = time.time() + timeout_min * 60
    print(f"== watch repo1（{timeout_min} 分钟上限）…")
    while time.time() < deadline:
        codes = []
        for u in urls:
            try:
                with urllib.request.urlopen(urllib.request.Request(u, method="HEAD"), timeout=20) as r:
                    codes.append(r.status)
            except urllib.error.HTTPError as e:
                codes.append(e.code)
            except Exception:
                codes.append(0)
        print(f"   {time.strftime('%H:%M:%S')} " +
              " ".join(f"{a}={c}" for a, c in zip(ARTIFACTS, codes)))
        if all(c == 200 for c in codes):
            print(f"== PUBLISHED 🎉  https://central.sonatype.com/artifact/{GROUP}/{ARTIFACTS[0]}/{version}")
            return
        time.sleep(60)
    die(f"{timeout_min} 分钟内 repo1 未出现 {version} —— 去 https://central.sonatype.com的确 "
        f"（网页登录后 Publish → Deployments 看 {GROUP} 的验证错误），修复后重跑 release.sh。")


def main() -> None:
    argv = [a for a in sys.argv[1:] if not a.startswith("--")]
    cmd = argv[0] if argv else ""
    if cmd == "check":
        need_creds()
        print("✓ Central 令牌与 GPG 口令就绪")
        return
    if cmd in ("bundle", "upload", "full", "watch") and len(argv) < 2:
        die(f"用法: publish-central.py {cmd} <version> [--timeout-min N]")
    if cmd == "bundle":
        cmd_bundle(argv[1])
    elif cmd == "upload":
        cmd_upload(argv[1])
    elif cmd == "status":
        if not argv[1:]:
            die("用法: publish-central.py status <deploymentId>")
        cmd_status(argv[1])
    elif cmd == "watch":
        tm = 45
        if "--timeout-min" in sys.argv:
            tm = int(sys.argv[sys.argv.index("--timeout-min") + 1])
        cmd_watch(argv[1], tm)
    elif cmd == "full":
        v = argv[1]
        cmd_bundle(v)
        cmd_upload(v)
        tm = 45
        if "--timeout-min" in sys.argv:
            tm = int(sys.argv[sys.argv.index("--timeout-min") + 1])
        cmd_watch(v, tm)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
