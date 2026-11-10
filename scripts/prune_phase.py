#!/usr/bin/env python3
"""merge된 완료 phase의 계획 파일을 삭제하고 정리 커밋을 남긴다. 보관은 git 히스토리에 맡긴다."""

import argparse
import shutil
import sys

import execute as harness


def check_prunable(phase_name: str, delete_logs: bool) -> list[str]:
    """정리할 수 없는 사유 목록을 반환한다. 비어 있으면 정리 가능."""
    phase_dir = harness.PHASES_DIR / phase_name
    if not (phase_dir / "index.json").is_file():
        return [f"phases/{phase_name}/index.json이 없습니다."]
    problems = []
    # 최상위 index.json에는 상태가 없다. step 커밋에 포함되는 phase index의 step 상태로 완료를 판정한다.
    steps = harness.load_phase_index(phase_dir)["steps"]
    unfinished = [f"step{s['step']:02d}({s['status']})" for s in steps if s["status"] != "completed"]
    if not steps or unfinished:
        problems.append(f"완료되지 않은 step: {', '.join(unfinished) or '없음(step 0개)'}")
    tracked = harness._git("ls-files", "--", f"phases/{phase_name}").stdout.split()
    if not tracked:
        problems.append("git에 추적되는 phase 파일이 없습니다. merge 여부를 확인하세요.")
    dirty = harness._git("status", "--porcelain", "--", "phases/index.json", f"phases/{phase_name}").stdout.strip()
    if dirty:
        problems.append(f"커밋되지 않은 변경이 있습니다:\n{dirty}")
    logs = phase_dir / "logs"
    if logs.is_dir() and any(logs.iterdir()) and not delete_logs:
        problems.append(
            "실행 로그가 남아 있습니다. 측정값이 필요하면 먼저 내보낸 뒤 --delete-logs로 다시 실행하세요.\n"
            f"  예: rsync -a --include='*/' --include='*.json' --exclude='*' "
            f"phases/{phase_name}/logs/ ~/harness-metrics/{phase_name}/"
        )
    return problems


def prune(phase_name: str, *, tag: bool = False, delete_logs: bool = False, dry_run: bool = False) -> int:
    problems = check_prunable(phase_name, delete_logs)
    if problems:
        for problem in problems:
            print(f"✗ {problem}", file=sys.stderr)
        return 1

    tag_name = f"phase/{phase_name}"
    plan = [f"git rm -r phases/{phase_name}", f"phases/index.json에서 '{phase_name}' 항목 제거"]
    if tag:
        plan.insert(0, f"git tag {tag_name} (정리 직전 HEAD)")
    if delete_logs:
        plan.append(f"phases/{phase_name}/logs 삭제")
    plan.append(f"커밋: chore: 완료 phase 정리 ({phase_name})")
    for line in plan:
        print(f"{'[dry-run] ' if dry_run else ''}- {line}")
    if dry_run:
        return 0

    def git(*args: str) -> None:
        result = harness._git(*args)
        if result.returncode != 0:
            raise RuntimeError(f"git {args[0]} 실패: {(result.stderr or result.stdout).strip()}")

    if tag:
        git("tag", tag_name)
    git("rm", "-r", "-q", "--", f"phases/{phase_name}")
    # gitignore된 logs/만 남은 디렉토리를 지운다. --delete-logs 없이 여기 왔다면 logs는 비어 있다.
    shutil.rmtree(harness.PHASES_DIR / phase_name, ignore_errors=True)
    data = harness.load_phases_index()
    data["phases"] = [p for p in data["phases"] if p["dir"] != phase_name]
    harness.save_phases_index(data)
    # 다른 스테이징 변경을 섞지 않도록 정리 대상 경로만 커밋한다.
    git("add", "--", "phases/index.json")
    git("commit", "-q", "-m", f"chore: 완료 phase 정리 ({phase_name})", "--", "phases/index.json", f"phases/{phase_name}")
    print(f"✓ '{phase_name}' 정리 완료. 복원: git log -- phases/{phase_name}" + (f" 또는 git show {tag_name}" if tag else ""))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="merge된 완료 phase의 계획 파일(index.json, step 지시서)을 삭제하고 커밋한다. "
        "메인 체크아웃에서 merge 후 실행한다.",
    )
    parser.add_argument("phase", help="phases/ 아래 phase 디렉토리명")
    parser.add_argument("--tag", action="store_true", help="정리 직전 HEAD에 phase/<phase> 태그를 남긴다")
    parser.add_argument("--delete-logs", action="store_true", help="gitignore된 실행 로그도 삭제한다")
    parser.add_argument("--dry-run", action="store_true", help="검사와 작업 목록만 출력한다")
    args = parser.parse_args(argv)
    try:
        return prune(args.phase, tag=args.tag, delete_logs=args.delete_logs, dry_run=args.dry_run)
    except RuntimeError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
