#!/usr/bin/env python3
"""R8 mapping 反混淆工具：把混淆后的崩溃堆栈还原为原始类名 / 方法名。

R8 每次 release 构建都会生成完整映射表（原名 -> 混淆名），默认位置：
    app/build/outputs/mapping/release/mapping.txt
归档副本（配合 build.gradle.kts 的 archiveReleaseMapping 任务）位置：
    app/build/outputs/mapping/archive/mapping-<versionName>-<时间戳>.txt

用法：
    python3 tools/retrace.py --mapping <mapping.txt> [stack.txt]
    cat stack.txt | python3 tools/retrace.py -m app/build/outputs/mapping/release/mapping.txt
    # 不带 stack 文件时从标准输入读取；不带 --mapping 时自动查找最新归档 mapping。

输出说明：
    - 类名 / 方法名还原为原始名称；
    - 堆栈文件名显示为 "SourceFile"（R8 默认重命名）时，用 mapping 中该类
      的源文件信息还原为 <原文件名>:<行号>。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from typing import Dict, List, Optional, Tuple

# 类行：原名 -> 混淆名:
CLASS_LINE = re.compile(r'^(\S.*?)\s+->\s+([^:]+):\s*$')
# 成员行（缩进）：<返回类型 方法名(参数)> 或 <类型 字段名> -> 混淆名
MEMBER_LINE = re.compile(r'^(\s+)(\S.*?)\s+->\s+([^:]+?)\s*:?\s*$')
# 源文件元数据行：# {"id":"sourceFile","fileName":"Foo.kt"}
SOURCEFILE_META = re.compile(r'^\s*#\s*\{.*"fileName"\s*:\s*"([^"]+)"')
# 堆栈行中的 类.方法( 片段（at 之后）；方法名兼容 <init> / <clinit>
_STACK_CLS = r'(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*'
_STACK_METHOD = r'(?:<init>|<clinit>|[A-Za-z_$][\w$]*)'
STACK_AT = re.compile(r'\bat\s+(' + _STACK_CLS + r')\.(' + _STACK_METHOD + r')\(')
# 方法描述里的"原类全名.方法名"限定（返回类型之后、括号之前的最后一个 token）
DOTTED_MEMBER = re.compile(
    r'((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*)\.((?:<init>|<clinit>|[A-Za-z_$][\w$]*))$'
)


def method_name(desc: str) -> str:
    """从方法描述中提取方法名。

    常规描述形如 "返回类型 方法名(参数...)"；
    R8 对带定位信息的成员行会写成 "范围:返回类型 原类名.方法名(参数...)"，
    方法名前缀带类限定符，需取最后一个 '.' 之后的部分。
    """
    paren = desc.find('(')
    head = desc[:paren].strip() if paren >= 0 else desc.strip()
    if not head:
        return ''
    if head.startswith('<'):
        return head
    token = head.rsplit(None, 1)[-1] if ' ' in head else head
    return token.rsplit('.', 1)[-1]


class Mapping:
    def __init__(self) -> None:
        # obf_class -> orig_class
        self.classes: Dict[str, str] = {}
        # obf_class -> 源文件名（可为空）
        self.src_files: Dict[str, str] = {}
        # obf_class -> {obf_member -> [完整描述]}
        self.members: Dict[str, Dict[str, List[str]]] = {}
        # obf_member -> [(obf_class, 完整描述)]，供跨类回退匹配
        self.member_index: Dict[str, List[Tuple[str, str]]] = {}
        # 带"原类名.方法名"限定的方法行索引（类未被重命名但方法被混淆时使用）
        # obf_member -> {(orig_class, orig_method)}
        self.qualified: Dict[str, set] = {}

    @classmethod
    def parse(cls, path: str) -> "Mapping":
        mapping = cls()
        cur_obf: Optional[str] = None
        with open(path, 'r', encoding='utf-8', errors='replace') as fh:
            for line in fh:
                line = line.rstrip('\n')
                if not line.strip():
                    continue
                if line.startswith('#'):
                    m = SOURCEFILE_META.match(line)
                    if m and cur_obf is not None:
                        mapping.src_files.setdefault(cur_obf, m.group(1))
                    continue
                cls_m = CLASS_LINE.match(line)
                if cls_m and not line[0].isspace():
                    orig, obf = cls_m.group(1).strip(), cls_m.group(2).strip()
                    cur_obf = obf
                    if obf.startswith('R8$$REMOVED$$CLASS'):
                        continue
                    mapping.classes[obf] = orig
                    mapping.members.setdefault(obf, {})
                    continue
                mem_m = MEMBER_LINE.match(line)
                if mem_m and cur_obf is not None:
                    desc = mem_m.group(2).strip()
                    obf_member = mem_m.group(3).strip()
                    if cur_obf not in mapping.members:
                        continue
                    bucket = mapping.members[cur_obf].setdefault(obf_member, [])
                    if desc not in bucket:
                        bucket.append(desc)
                    mapping.member_index.setdefault(obf_member, []).append((cur_obf, desc))
                    # 描述带类限定（"返回类型 原类名.方法名(参数)"）时额外索引，
                    # 用于类本身未重命名、只有方法被混淆的还原（如 Media3 被 keep 的类）
                    paren = desc.find('(')
                    if paren >= 0:
                        head = desc[:paren]
                        dm = DOTTED_MEMBER.search(head)
                        if dm:
                            mapping.qualified.setdefault(obf_member, set()).add(
                                (dm.group(1), dm.group(2))
                            )
        return mapping

    def resolve(self, obf_class: str, obf_method: str) -> Optional[Tuple[str, str]]:
        """返回 (原始类名, 原始方法名)；找不到返回 None。"""
        orig = self.classes.get(obf_class)
        if orig is None:
            # 前缀回退：R8 堆栈可能把嵌套类打印成外层混淆名
            for dot in range(len(obf_class) - 1, -1, -1):
                if obf_class[dot] == '.' and obf_class[:dot] in self.classes:
                    orig = self.classes[obf_class[:dot]]
                    obf_class = obf_class[:dot]
                    break
        # 情况一：混淆类有类行，从该类成员映射还原
        if orig is not None:
            descs = self.members.get(obf_class, {}).get(obf_method, [])
            if descs:
                return (orig, method_name(descs[0]))
        else:
            # 情况二：堆栈类名即原始类名（类未重命名，仅方法被混淆）
            orig = obf_class
        # 情况三：通过带类限定的方法行（原类名.方法名 -> 混淆名）反查
        if obf_method in self.qualified:
            hits = [q for q in self.qualified[obf_method] if q[0] == orig]
            if hits:
                # 同一方法多个定位行会重复，取任一条即可
                return hits[0]
        # 兜底：全表唯一混淆方法名匹配
        if orig is None:
            return None
        fallback = [q for q in self.qualified.get(obf_method, set())]
        if len(fallback) == 1:
            return fallback[0]
        return None

    def source_file_for(self, obf_class: str) -> Optional[str]:
        return self.src_files.get(obf_class)


def retrace_line(mapping: Mapping, line: str) -> str:
    m = STACK_AT.search(line)
    if not m:
        return line
    obf_class, obf_method = m.group(1), m.group(2)
    resolved = mapping.resolve(obf_class, obf_method)
    if not resolved:
        return line
    orig_class, orig_method = resolved
    start, end = m.start(1), m.end(2)
    line = line[:start] + orig_class + '.' + orig_method + line[end:]

    # 还原文件名：SourceFile/Unknown Source -> mapping 记录的源文件名
    rest = line[end:]
    fm = re.search(r'\(([^):]+)(?::(\d+))?\)', rest)
    if fm and fm.group(1) in ('SourceFile', 'Unknown Source', 'sourcestub'):
        src = mapping.source_file_for(obf_class)
        if src:
            line_no = fm.group(2) or ''
            suffix = ':' + line_no if line_no else ''
            line = line[:end] + line[end:].replace(fm.group(0), f'({src}{suffix})', 1)
    return line


def find_latest_archive(mapping_defaults: List[str]) -> Optional[str]:
    """依次尝试：最新归档 mapping -> 当前构建 mapping。"""
    for pat in mapping_defaults:
        hits = sorted(glob.glob(pat), key=os.path.getmtime, reverse=True)
        if hits:
            return hits[0]
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description='R8 mapping 反混淆工具')
    parser.add_argument('-m', '--mapping', help='mapping.txt 路径（缺省自动找最新归档）')
    parser.add_argument('stack', nargs='?', help='混淆堆栈文件（缺省读标准输入）')
    args = parser.parse_args()

    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    mapping_path = args.mapping or find_latest_archive([
        os.path.join(project_root, 'app/build/outputs/mapping/archive/mapping-*.txt'),
        os.path.join(project_root, 'app/build/outputs/mapping/release/mapping.txt'),
    ])
    if not mapping_path:
        print('未找到 mapping 文件：请用 -m 指定，或先运行 assembleRelease', file=sys.stderr)
        return 1

    print(f'# 使用 mapping：{mapping_path}', file=sys.stderr)
    mapping = Mapping.parse(mapping_path)

    if args.stack:
        with open(args.stack, 'r', encoding='utf-8', errors='replace') as fh:
            lines = fh.read().splitlines()
    else:
        lines = sys.stdin.read().splitlines()
    for line in lines:
        print(retrace_line(mapping, line))
    return 0


if __name__ == '__main__':
    sys.exit(main())
