"""Back up or recover a stopped local H2 installation, including its attachments."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

DATABASE = "demo-db.mv.db"
MANIFEST = "manifest.json"


def digest(stream):
    sha = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        sha.update(chunk)
    return sha.hexdigest()


def backup(runtime, archive):
    database = runtime / DATABASE
    if not database.is_file():
        raise ValueError(f"未找到数据库 {database}")
    if (runtime / "demo-db.lock.db").exists():
        raise ValueError("请先停止后端再备份")
    files = [database, *(p for p in (runtime / "files").rglob("*") if p.is_file())]
    entries = {}
    with tempfile.TemporaryDirectory(dir=archive.parent) as work:
        staged = Path(work) / "backup.zip"
        with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED) as output:
            for path in files:
                if not path.resolve().is_relative_to(runtime) or path.is_symlink():
                    raise ValueError(f"拒绝备份运行目录外的文件 {path}")
                name = path.relative_to(runtime).as_posix()
                output.write(path, name)
        with zipfile.ZipFile(staged, "a") as output:
            for name in output.namelist():
                with output.open(name) as stream:
                    entries[name] = digest(stream)
            output.writestr(MANIFEST, json.dumps(entries, ensure_ascii=False, indent=2))
        if archive.exists():
            raise FileExistsError(archive)
        staged.rename(archive)


def restore(archive, destination):
    if destination.exists():
        raise FileExistsError("恢复目标必须是全新目录")
    with zipfile.ZipFile(archive) as source, tempfile.TemporaryDirectory(dir=destination.parent) as work:
        expected = json.loads(source.read(MANIFEST))
        names = source.namelist()
        if DATABASE not in expected or len(names) != len(set(names)) or set(names) != {*expected, MANIFEST}:
            raise ValueError("备份清单不完整或文件重复")
        staged = Path(work) / "runtime"
        (staged / "files").mkdir(parents=True)
        for name, sha in expected.items():
            parts = PurePosixPath(name).parts
            if (name != DATABASE and (not parts or parts[0] != "files" or len(parts) < 2)
                    or ".." in parts or chr(92) in name or ":" in name):
                raise ValueError(f"备份包含非法路径 {name}")
            target = staged.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(name) as stream, target.open("xb") as out:
                shutil.copyfileobj(stream, out)
            with target.open("rb") as stream:
                if digest(stream) != sha:
                    raise ValueError(f"备份校验失败 {name}")
        staged.rename(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["backup", "restore"])
    parser.add_argument("runtime", type=Path)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        runtime = args.runtime.resolve()
        archive = args.archive.resolve()
        if args.mode == "backup":
            backup(runtime, archive)
        else:
            restore(archive, runtime)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()
