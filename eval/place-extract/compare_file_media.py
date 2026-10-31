"""저장된 batch/<영상ID>/download/*.ko.vtt 및 frames/*.jpg로 파일 입력 비교.
실행 전 Gemini 사용량 확인. 호출 약 8회. python3 eval/place-extract/compare_file_media.py <batch 폴더>
2건만 비교하므로 전체 품질을 일반화하지 않는다. 캐시를 사용하지 않는다.
"""
import csv,json,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'pipeline-test'))
import extract_places as ex
import download
cache=Path(sys.argv[1])
answers=list(csv.DictReader((root/'eval/extraction/answers.csv').open()))
videos=list(csv.DictReader((root/'eval/extraction/videos.csv').open()))
out=root/'docs/eval/file-media-filter-comparison.json'
results=[]
norm=lambda s: ''.join(s.split())
for number in ['7','10']:
 video=next(v for v in videos if v['video_no']==number)
 work=cache/video['url'].rsplit('/',1)[-1]
 caption=next((work/'download').glob('*.ko.vtt'))
 frames=sorted((work/'frames').glob('*.jpg'))[:8]
 transcript=download.vtt_to_text(caption)
 expected=[norm(a['place_name']) for a in answers if a['video_no']==number and a['place_name'] and a['is_destination']!='N']
 for flag in [False,True]:
  ex.FILTER_TEXT_ONLY=flag
  start=time.monotonic()
  try:
   places=ex.extract_places(transcript=transcript,frame_paths=frames)
   names=[norm(p['name']) for p in places]
   same=lambda a,b:a==b or a in b or b in a
   recall=sum(any(same(e,n) for n in names) for e in expected)/len(expected)
   precision=sum(any(same(e,n) for e in expected) for n in names)/len(names) if names else 1
   row=dict(video_no=number,title=video['title'],flag=flag,seconds=round(time.monotonic()-start,3),recall=recall,precision=precision,names=names,expected=expected,caption_chars=len(transcript),frames=len(frames))
  except (Exception,SystemExit) as err:
   row=dict(video_no=number,flag=flag,error=str(err))
  results.append(row)
  out.write_text(json.dumps({'scope':'Saved captions + frames; two cases only; not representative of Instagram or full pipeline','results':results},ensure_ascii=False,indent=2)+'\n')
  print(json.dumps(row,ensure_ascii=False),flush=True)
  time.sleep(6)
