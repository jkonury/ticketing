#!/usr/bin/env python3
"""Shared Bash hook policy. This is a guardrail, not a shell security boundary."""

import ast
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import token
import tokenize
from collections.abc import Collection, Iterator, Sequence


def git(root: Path, *args: str) -> str:
    return subprocess.run(
        ['git', '-C', str(root), *args], capture_output=True, text=True, check=True, timeout=5
    ).stdout


def mask_python_strings(source: str) -> str:
    """Python 문자열을 shell token으로 오인하지 않게 하되 코드와 줄 경계는 유지한다."""
    try:
        ast.parse(source)
        tokens = list(tokenize.generate_tokens(io.StringIO(source).readline))
    except (SyntaxError, tokenize.TokenError, IndentationError) as exc:
        raise ValueError('invalid Python heredoc') from exc
    lines = source.splitlines(keepends=True)
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    chars = list(source)
    literal_types = {token.STRING}
    for name in ('FSTRING_START', 'FSTRING_MIDDLE', 'FSTRING_END',
                 'TSTRING_START', 'TSTRING_MIDDLE', 'TSTRING_END'):
        if hasattr(token, name):
            literal_types.add(getattr(token, name))
    for item in tokens:
        if item.type not in literal_types:
            continue
        start = offsets[item.start[0] - 1] + item.start[1]
        end = offsets[item.end[0] - 1] + item.end[1]
        for index in range(start, end):
            if chars[index] not in '\r\n':
                chars[index] = ' '
    return ''.join(chars)


def without_literal_heredocs(command: str) -> str:
    """인용된 데이터 heredoc과 단독 Python heredoc의 문자열을 처리한다."""
    pending = []
    python_body = []
    result = []
    for line in command.splitlines(keepends=True):
        if pending:
            delimiter, strip_tabs, kind = pending[0]
            candidate = line.lstrip('\t') if strip_tabs else line
            if candidate.rstrip('\r\n') == delimiter:
                pending.pop(0)
                if kind == 'python':
                    result.append(mask_python_strings(''.join(python_body)))
                    python_body.clear()
                result.append('\n')
            elif kind == 'python':
                python_body.append(candidate)
            else:
                result.append('\n')
            continue
        result.append(line)
        if '<<' not in line:
            continue
        lexer = shlex.shlex(line, posix=False, punctuation_chars=';&|()<>')
        lexer.whitespace_split = True
        try:
            parts = list(lexer)
        except ValueError:
            continue
        # shell에 전달하는 본문 및 pipeline·제어문은 이 예외로 숨기지 않는다.
        is_python = (len(parts) == 4 and re.fullmatch(r'python(?:3(?:\.\d+)?)?', Path(parts[0]).name)
                     and parts[1:3] == ['-', '<<'])
        if not parts or (Path(parts[0]).name not in {'cat', 'tee', ':'} and not is_python) or any(
                part and all(c in ';&|()' for c in part) for part in parts):
            continue
        candidates = []
        for index, part in enumerate(parts[:-1]):
            if part != '<<':
                continue
            delimiter = parts[index + 1]
            strip_tabs = delimiter.startswith('-')
            delimiter = delimiter[1:] if strip_tabs else delimiter
            if len(delimiter) > 2 and delimiter[0] in {'"', "'"} and delimiter[-1] == delimiter[0]:
                candidates.append((delimiter[1:-1], strip_tabs, 'python' if is_python else 'literal'))
            else:
                candidates = []
                break
        pending.extend(candidates)
    if any(kind == 'python' for _, _, kind in pending):
        raise ValueError('unterminated Python heredoc')
    return ''.join(result)


def tokens(command: str) -> list[str]:
    command = without_literal_heredocs(command)
    lexer = shlex.shlex(command, posix=True, punctuation_chars=';&|()<>\n')
    lexer.whitespace = ' \t\r'
    lexer.whitespace_split = True
    lexer.commenters = '#'
    result = []
    for word in lexer:
        # shlex가 묶은 (), ;\n 등은 개별 shell 경계로 나눈다.
        if word and all(c in ';&|()<>\n' for c in word):
            result.extend(re.findall(r'&&|\|\||\|&|;;&|;;|;&|[;&|()<>\n]', word))
        else:
            result.append(word)
    return result


def deletion_reason(root: Path, cwd: Path, target: str) -> str | None:
    if any(c in target for c in '$`*?[]{}~'):
        return 'Recursive deletion requires a literal path'
    path = Path(target)
    path = path if path.is_absolute() else cwd / path
    if path.is_symlink():
        return 'Recursive deletion of a symlink requires manual review'
    path = path.resolve()
    if not path.is_relative_to(root) or path == root or '.git' in path.relative_to(root).parts:
        return f'Recursive deletion outside project files: {target}'
    relative = path.relative_to(root).as_posix()
    # HEAD comparison catches both staged and unstaged modifications.
    if git(root, 'diff', 'HEAD', '--', relative):
        return f'Recursive deletion would lose uncommitted changes: {target}'
    tracked = set(git(root, 'ls-files', '-z', '--', relative).split('\0'))
    entries = [path] if path.is_file() else []
    if path.is_dir():
        for parent, directories, files in os.walk(path):
            entries.extend(Path(parent) / name for name in files)
            entries.extend(Path(parent) / name for name in directories if (Path(parent) / name).is_symlink())
    for entry in entries:
        if entry.relative_to(root).as_posix() not in tracked:
            return f'Recursive deletion would lose untracked or ignored files: {target}'
    return None


SHELLS = {'sh', 'bash', 'zsh', 'dash'}
# Prefixes that run the following word as a command (sudo after them is still sudo).
WRAPPERS = {'env', 'command', 'exec', 'nohup', 'time', 'timeout', 'nice', 'ionice', 'xargs', 'stdbuf', 'setsid'}
SUDO_HINT = 'sudo is blocked; ask the user to run privileged commands manually'
RESERVED_PREFIXES = {'if', 'then', 'elif', 'else', 'while', 'until', 'do', '!', '{'}
BOUNDARIES = {';', ';;', ';&', ';;&', '&&', '||', '|', '|&', '&', '\n', '(', ')'}
APPROVAL_HINT = 'This changes running services or containers; confirm with the user before running.'
MERGE_HINT = 'PR merge changes shared history; confirm with the user before running.'
MAIN_HINT = 'This commits or pushes to main; confirm the user intends to work on main.'


def is_command_position(words: Sequence[str], index: int, boundaries: Collection[str]) -> bool:
    start = index
    while start > 0 and words[start - 1] not in boundaries:
        start -= 1
    skip_value = False
    for word in words[start:index]:
        if skip_value:
            skip_value = False
            continue
        if word in {'-u', '--unset', '-C', '--chdir', '-n', '--adjustment', '-I', '-P', '-s'}:
            skip_value = True
        elif not (Path(word).name in WRAPPERS or word in RESERVED_PREFIXES
                  or word.startswith('-') or word[:1].isdigit()
                  or ('=' in word and not word.startswith('='))):
            return False
    # command -v/-V는 실행 대신 명령 이름을 조회한다.
    return not (skip_value or ('command' in words[start:index]
                               and any(w in {'-v', '-V'} for w in words[start:index])))


def invocations(command: str) -> Iterator[tuple[str, list[str]]]:
    words = tokens(command)
    for index, word in enumerate(words):
        if not is_command_position(words, index, BOUNDARIES):
            continue
        end = index + 1
        while end < len(words) and words[end] not in BOUNDARIES:
            end += 1
        name, args = Path(word).name, words[index + 1:end]
        yield name, args
        if name in SHELLS and any(a.startswith('-') and not a.startswith('--') and 'c' in a for a in args):
            for nested in args:
                if not nested.startswith('-'):
                    yield from invocations(nested)
                    break
        if name == 'eval' and args:
            yield from invocations(' '.join(args))


def option_parts(args: Sequence[str], value_options: Collection[str] = ()) -> tuple[list[str], list[str]]:
    """옵션의 값과 -- 뒤의 파일명은 flag 검사에서 제외한다."""
    options, operands = [], []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == '--':
            operands.extend(args[index + 1:])
            break
        if arg.startswith('-') and arg != '-':
            consumes_value = arg in value_options
            if not arg.startswith('--'):
                for offset, flag in enumerate(arg[1:], 1):
                    option = '-' + flag
                    options.append(option)
                    if option in value_options:
                        consumes_value = offset == len(arg) - 1
                        break
            else:
                options.append(arg)
            if consumes_value:
                index += 1
        else:
            operands.append(arg)
        index += 1
    return options, operands


def short_flag(options: Sequence[str], flags: str) -> bool:
    return any(a.startswith('-') and not a.startswith('--') and any(f in a[1:] for f in flags)
               for a in options)


def git_command(args: Sequence[str], cwd: Path) -> tuple[str, list[str], Path, bool]:
    """Git global 옵션을 건너뛰고 -C 기준 경로와 hook override를 확인한다."""
    index, hook_override = 0, False
    while index < len(args) and args[index].startswith('-'):
        arg = args[index]
        value = ''
        if arg in {'-C', '-c', '--git-dir', '--work-tree', '--namespace', '--config-env'}:
            index += 1
            if index >= len(args):
                raise ValueError('missing Git global option value')
            value = args[index]
        elif arg.startswith(('-C', '-c')) and not arg.startswith('--'):
            value = arg[2:]
        elif '=' in arg:
            value = arg.split('=', 1)[1]
        if arg == '-C' or (arg.startswith('-C') and not arg.startswith('--')):
            cwd = (cwd / value).resolve()
        if arg == '-c' or arg.startswith('-c') or arg.startswith('--config-env'):
            key, _, setting = value.partition('=')
            if key.lower() == 'core.hookspath' and (arg.startswith('--config-env') or setting != '.githooks'):
                hook_override = True
        index += 1
    return (args[index] if index < len(args) else '', list(args[index + 1:]), cwd, hook_override)


def git_reason(args: Sequence[str], cwd: Path) -> str | None:
    subcommand, args, target_cwd, hook_override = git_command(args, cwd)
    if hook_override:
        return 'Disabling or replacing repository Git hooks is blocked'
    options, operands = option_parts(args, {'-m', '--message', '-o', '--push-option', '--repo',
                                           '--receive-pack', '--exec', '-b', '-B', '--source', '-s'})
    if '--no-verify' in options or (subcommand == 'commit' and short_flag(options, 'n')):
        return 'Skipping Git verification is blocked'
    if subcommand == 'config':
        # 조회 및 관리 중인 .githooks 경로 설정은 허용한다.
        keys = [i for i, a in enumerate(operands) if a.lower() == 'core.hookspath']
        for index in keys:
            if any(a in options for a in {'--unset', '--unset-all', '--remove-section'}) or (
                    index + 1 < len(operands) and operands[index + 1] != '.githooks'):
                return 'Disabling or replacing repository Git hooks is blocked'
    if subcommand == 'push' and (short_flag(options, 'f') or any(a.startswith('--force') for a in options)
                                 or '--mirror' in options or any(a.startswith('+') for a in operands)):
        return 'Force push is blocked'
    if subcommand == 'reset' and '--hard' in options:
        return 'Hard reset is blocked'
    if subcommand == 'clean' and (short_flag(options, 'f') or '--force' in options):
        return 'Forced Git clean is blocked'
    if subcommand == 'branch' and (short_flag(options, 'dD') or '--delete' in options):
        return 'Branch deletion is blocked'
    if subcommand in {'checkout', 'restore'}:
        root = Path(git(target_cwd, 'rev-parse', '--show-toplevel').strip()).resolve()
        for operand in operands:
            if operand in {':/', ':/*', ':(top)', ':(top).', ':(top)*'} or (
                    not operand.startswith('-') and (target_cwd / operand).resolve() == root):
                return 'Restoring the entire working directory is blocked'
        if '.' in operands:
            return 'Restoring the entire working directory is blocked'
    return None


def docker_action(name: str, args: Sequence[str]) -> tuple[str, list[str]]:
    if name == 'docker-compose':
        return 'compose', list(args)
    index = 0
    while index < len(args) and args[index].startswith('-'):
        if args[index] in {'--context', '-c', '--host', '-H', '--config', '--log-level', '-l'}:
            index += 1
        index += 1
    return (args[index], list(args[index + 1:])) if index < len(args) else ('', [])


def docker_reason(name: str, args: Sequence[str]) -> str | None:
    action, operation, rest = docker_operation(name, args)
    options, _ = option_parts(rest, {'--timeout', '-t'})
    if action == 'volume' and operation in {'rm', 'remove', 'prune'}:
        return 'Docker volume deletion is blocked (AGENTS.md data preservation)'
    removes_data = (action == 'compose' and operation in {'down', 'rm'}) or (
        action == 'system' and operation == 'prune') or operation in {'rm', 'remove'}
    volumes = any(a.split('=', 1)[0] == '--volumes' and (
        '=' not in a or a.split('=', 1)[1].lower() not in {'false', '0', 'f'}) for a in options)
    if removes_data and (short_flag(options, 'v') or volumes):
        return 'Docker volume deletion is blocked (AGENTS.md data preservation)'
    return None


def docker_operation(name: str, args: Sequence[str]) -> tuple[str, str, list[str]]:
    action, rest = docker_action(name, args)
    if action not in {'compose', 'container', 'volume', 'system'}:
        return action, action, rest
    index = 0
    while index < len(rest) and rest[index].startswith('-'):
        if rest[index] in {'-f', '--file', '-p', '--project-name', '--env-file',
                           '--profile', '--project-directory', '--ansi', '--progress', '--parallel'}:
            index += 1
        index += 1
    return (action, rest[index], rest[index + 1:]) if index < len(rest) else (action, '', [])


def inspect(command: str, cwd: Path) -> tuple[str | None, list[str]]:
    notices = []
    for name, args in invocations(command):
        if name == 'sudo':
            return SUDO_HINT, notices
        if name == 'git':
            denied = git_reason(args, cwd)
            if denied:
                return denied, notices
            subcommand, git_args, target_cwd, _ = git_command(args, cwd)
            if subcommand in {'commit', 'push'}:
                branch = git(target_cwd, 'rev-parse', '--abbrev-ref', 'HEAD').strip()
                if branch == 'main' or (subcommand == 'push' and any(
                        a == 'main' or a.endswith((':main', ':refs/heads/main')) for a in git_args)):
                    notices.append(MAIN_HINT)
        if name in {'docker', 'docker-compose'}:
            denied = docker_reason(name, args)
            if denied:
                return denied, notices
            _, operation, _ = docker_operation(name, args)
            if operation in {'up', 'down', 'start', 'stop', 'restart', 'kill', 'rm', 'remove', 'run', 'prune'}:
                notices.append(APPROVAL_HINT)
        if name == 'gh':
            options, operands = option_parts(args, {'-R', '--repo', '--hostname'})
            if operands[:2] == ['pr', 'merge']:
                if any(a.split('=', 1)[0] in {'--admin', '--delete-branch'} for a in options) or short_flag(options, 'd'):
                    return 'PR merge with --admin or --delete-branch is blocked', notices
                notices.append(MERGE_HINT)
        if name == 'systemctl' and any(a in {'restart', 'reload', 'start', 'stop', 'enable', 'disable',
                                           'daemon-reload', 'mask', 'unmask', 'reload-or-restart',
                                           'try-restart'} for a in args):
            notices.append(APPROVAL_HINT)
        if name in {'apt', 'apt-get', 'dpkg', 'ufw'} and any(a in {
                'install', 'remove', 'purge', 'upgrade', 'full-upgrade', 'dist-upgrade', 'autoremove',
                'allow', 'deny', 'reject', 'delete', 'enable', 'disable', 'reset', 'reload'} for a in args):
            notices.append(APPROVAL_HINT)
        if name == 'rm' and any(a == '--recursive' or (a.startswith('-') and not a.startswith('--') and 'r' in a.lower()) for a in args):
            # shell 확장과 디렉터리 이동의 결과는 shlex로 확정할 수 없다.
            if any(n == 'cd' for n, _ in invocations(command)):
                return 'Run recursive deletion separately from directory changes', notices
            if any(c in command for c in '$`<>'):
                return 'Recursive deletion with shell expansion requires manual review', notices
            root = Path(git(cwd, 'rev-parse', '--show-toplevel').strip()).resolve()
            _, paths = option_parts(args)
            if not paths:
                return 'Recursive deletion requires an explicit path', notices
            for target in paths:
                denied = deletion_reason(root, cwd, target)
                if denied:
                    return denied, notices
    return None, list(dict.fromkeys(notices))


def reason(command: str, cwd: Path) -> str | None:
    return inspect(command, cwd)[0]


def main() -> None:
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError('hook input must be an object')
        event = payload.get('hook_event_name', 'PreToolUse')
        if event != 'PreToolUse' or payload.get('tool_name') != 'Bash':
            print('{}')
            return
        command = payload['tool_input']['command']
        if not isinstance(command, str) or not command.strip():
            raise ValueError('missing Bash command')
        denied, notices = inspect(command, Path(payload.get('cwd', os.getcwd())).resolve())
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        denied = f'Command policy could not validate input: {exc}'
    if denied:
        print(json.dumps({'hookSpecificOutput': {
            'hookEventName': 'PreToolUse', 'permissionDecision': 'deny',
            'permissionDecisionReason': denied,
        }}))
    elif notices:
        print(json.dumps({'hookSpecificOutput': {
            'hookEventName': 'PreToolUse', 'additionalContext': '\n'.join(notices),
        }}))
    else:
        # Leave authorization to the tool's existing permission configuration.
        print('{}')


if __name__ == '__main__':
    main()
