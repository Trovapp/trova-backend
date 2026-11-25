"""Build a Korean PDF from the checked-in report; no network requests."""
import html
import re
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, PageBreak

root = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck'
pdfmetrics.registerFont(TTFont('Malgun', 'C:/Windows/Fonts/malgun.ttf'))
pdfmetrics.registerFont(TTFont('MalgunBold', 'C:/Windows/Fonts/malgunbd.ttf'))
body = ParagraphStyle('body', fontName='Malgun', fontSize=8.5, leading=13, wordWrap='CJK', spaceAfter=5)
heading = ParagraphStyle('heading', parent=body, fontName='MalgunBold', fontSize=12, leading=17, spaceBefore=10, spaceAfter=7, keepWithNext=True)
caption = ParagraphStyle('caption', parent=body, keepWithNext=True)
small = ParagraphStyle('table', parent=body, fontSize=7, leading=10, spaceAfter=0)

def formatted(text):
    text = html.escape(text)
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1', text)
    text = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', text)
    return text.replace('`', '')

story = []
for filename in ['README.md', 'server-results.md', 'video-frame-observations.md', 'improvement-candidates.md', 'grafana-evidence.md']:
    if story:
        story.append(PageBreak())
    lines = (root/filename).read_text(encoding='utf-8').splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].startswith('|'):
                cells = [c.strip() for c in lines[i].strip('|').split('|')]
                if not all(re.fullmatch(r'[-: ]+', c) for c in cells):
                    rows.append([Paragraph(formatted(c), small) for c in cells])
                i += 1
            columns = max(map(len, rows))
            table = Table(rows, colWidths=[503/columns]*columns, repeatRows=1, hAlign='LEFT')
            table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e2ede9')), ('VALIGN',(0,0),(-1,-1),'TOP'), ('GRID',(0,0),(-1,-1),.25,colors.HexColor('#bbbbbb')), ('LEFTPADDING',(0,0),(-1,-1),4), ('RIGHTPADDING',(0,0),(-1,-1),4)]))
            story.extend([table, Spacer(1,8)])
            continue
        match = re.match(r'!\[([^\]]*)\]\(([^)]+)\)', line)
        if match and (root/match[2]).exists():
            story.append(Paragraph(formatted(match[1]), caption))
            image = Image(str(root/match[2]))
            scale = min(503/image.imageWidth, 620/image.imageHeight)
            image.drawWidth = image.imageWidth*scale
            image.drawHeight = image.imageHeight*scale
            story.extend([image, Spacer(1,8)])
        elif line.startswith('#'):
            story.append(Paragraph(formatted(line.lstrip('# ')), heading))
        elif line.strip():
            story.append(Paragraph(formatted(line), body))
        i += 1

def footer(canvas, doc):
    canvas.setFont('Malgun', 8)
    canvas.drawString(46, 23, 'Trova v11 성능 재측정 · 2026-11-25 · 운영 변경 없음')
    canvas.drawRightString(A4[0]-46, 23, str(doc.page))

SimpleDocTemplate(str(root/'v11-recheck-report.pdf'), pagesize=A4, leftMargin=46, rightMargin=46, topMargin=38, bottomMargin=42, title='Trova v11 성능 재측정', author='Trova').build(story, onFirstPage=footer, onLaterPages=footer)
