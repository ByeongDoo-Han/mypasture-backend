#!/usr/bin/env python3
"""두 단 개역한글 PDF에서 검증 가능한 장·절 JSON을 생성합니다."""

import argparse
import hashlib
import json
import re
import sqlite3
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path

import pdfplumber


ROOT = Path(__file__).resolve().parents[1]
BOOK_RE = re.compile(r'book\("([A-Z0-9]+)", "([^"]+)", Testament\.(OLD|NEW), (\d+), (\d+)\)')
VERSE_RE = re.compile(r'^([가-힣]{1,4})(\d+):(\d+)\s*(.*)$')
ABBREVIATIONS = ('창 출 레 민 신 수 삿 룻 삼상 삼하 왕상 왕하 대상 대하 스 느 에 욥 시 잠 전 아 사 렘 애 겔 단 호 욜 암 옵 욘 미 나 합 습 학 슥 말 마 막 눅 요 행 롬 고전 고후 갈 엡 빌 골 살전 살후 딤전 딤후 딛 몬 히 약 벧전 벧후 요일 요이 요삼 유 계').split()


def comparable(value):
    return re.sub(r'[^가-힣0-9 ]', '', re.sub(r'\([^)]*\)', '', value))


def join_parts(parts, reference):
    result = parts[0]
    for left, right in zip(parts, parts[1:]):
        choice = None
        if reference:
            target = comparable(reference)
            before, after = comparable(left), comparable(right)
            for length in (7, 5, 3, 2, 1):
                separated = before[-length:] + ' ' + after[:length] in target
                attached = before[-length:] + after[:length] in target
                if separated != attached:
                    choice = ' ' if separated else ''
                    break
            if choice is None:
                spaced = SequenceMatcher(None, comparable(result + ' ' + right), target).ratio()
                joined = SequenceMatcher(None, comparable(result + right), target).ratio()
                choice = ' ' if spaced >= joined else ''
        if choice is None:
            choice = '' if len(right.split()[0]) <= 2 and left[-1].isalpha() else ' '
        result += choice + right
    return re.sub(r'\s+', ' ', result).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pdf', type=Path, required=True)
    parser.add_argument('--reference-db', type=Path, required=True, help='기존 KOR1910 SQLite: PDF 줄바꿈의 띄어쓰기 대조에만 사용')
    parser.add_argument('--output', type=Path, default=ROOT / 'src/main/resources/bible/krv.json')
    args = parser.parse_args()

    catalog = ROOT / 'src/main/kotlin/com/example/mypasture/bible/domain/BibleCatalog.kt'
    books = [dict(code=code, name=name, testament=testament, displayOrder=int(order), chapterCount=int(count))
             for code, name, testament, order, count in BOOK_RE.findall(catalog.read_text(encoding='utf-8'))]
    assert len(books) == len(ABBREVIATIONS) == 66
    by_abbreviation = dict(zip(ABBREVIATIONS, (book['code'] for book in books)))
    headings = {book['name'] for book in books} | {'개역 한글 성경', '구약성경', '신약성경'}
    connection = sqlite3.connect(args.reference_db)
    reference = {(code, chapter, verse): text for code, chapter, verse, text in
                 connection.execute('SELECT book_code, chapter, verse, text FROM verses')}
    connection.close()

    rows = []
    current = None
    with pdfplumber.open(args.pdf) as pdf:
        assert len(pdf.pages) == 599, '예상과 다른 PDF 쪽수'
        for page in pdf.pages:
            for left, right in ((0, 753), (753, page.width)):
                text = page.crop((left, 0, right, page.height)).extract_text(x_tolerance=2, y_tolerance=3) or ''
                for raw in text.splitlines():
                    line = raw.strip()
                    match = VERSE_RE.match(line)
                    if match:
                        abbreviation, chapter, verse, body = match.groups()
                        assert abbreviation in by_abbreviation, f'알 수 없는 성경 약어: {abbreviation}'
                        current = (by_abbreviation[abbreviation], int(chapter), int(verse), [body])
                        rows.append(current)
                    elif line and line not in headings:
                        assert current is not None, f'절 이전의 본문: {line}'
                        current[3].append(line)

    chapters = defaultdict(list)
    for code, chapter, verse, parts in rows:
        value = join_parts(parts, reference.get((code, chapter, verse)))
        assert value, f'빈 절: {code} {chapter}:{verse}'
        chapters[(code, chapter)].append([verse, value])
    assert len(chapters) == 1189 and len(rows) == 31101, (len(chapters), len(rows))
    result = {}
    for book in books:
        result[book['code']] = {}
        for chapter in range(1, book['chapterCount'] + 1):
            verses = chapters[(book['code'], chapter)]
            numbers = [verse for verse, _ in verses]
            assert numbers == list(range(1, len(numbers) + 1)), f'절 누락/중복: {book["code"]} {chapter}'
            result[book['code']][str(chapter)] = verses

    payload = dict(version='KRV', name='개역한글판', source='성경전서 개역한글판, 대한성서공회 1961',
                   sourcePdfSha256=hashlib.sha256(args.pdf.read_bytes()).hexdigest(),
                   books=result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print(f'KRV: {len(books)} books, {len(chapters)} chapters, {len(rows)} verses, {args.output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
