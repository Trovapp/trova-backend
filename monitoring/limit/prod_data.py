#!/usr/bin/env python3
"""운영 부하 측정용 테스트 계정을 운영 DB에 만들고 지운다(#342, 사용자 승인 2026-11-08).

usage: PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/limit/prod_data.py seed|ids|cleanup
- seed: provider='loadtest' 사용자 50명과 그 사람들의 영상·저장 장소·찜·폴더·여행·알림을 한 트랜잭션으로 넣는다.
  찜은 기존 장소 목록(places)을 가리키고, 장소 목록에는 아무것도 넣지 않는다. 여행은 30일 뒤라 날씨 알림 대상이 아니다.
- ids: 테스트 사용자 id와 첫 여행 id를 JSON으로 출력한다(토큰 만들 때 쓴다).
- cleanup: provider='loadtest' 사용자와 딸린 데이터만 한 트랜잭션으로 지운다. 다른 표에 남은 행이 있으면 실패하고 되돌린다.
자격증명은 출력하지 않는다(prod_readonly_psql의 환경 파일 해석을 그대로 쓴다).
"""
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from prod_readonly_psql import find_psql, pg_env  # noqa: E402

USERS = 50
LT = "provider = 'loadtest'"

SEED = f"""
do $check$ begin
  if exists (select 1 from users where {LT}) then raise exception 'loadtest users already exist'; end if;
end $check$;
insert into users (provider, provider_user_id, nickname, created_at)
select 'loadtest', 'lt-'||u, 'loadtest-'||u, now() from generate_series(1,{USERS}) u;
create temp table lt_users on commit drop as
select id, substring(provider_user_id from 4)::int as n from users where {LT};
insert into processing_jobs (user_id,source_url,source_platform,status,retry_count,created_at,updated_at,analysis_status,analysis_finished_at)
select u.id, 'https://www.youtube.com/watch?v=loadtest-'||u.n||'-'||j, 'YOUTUBE','DONE',0,now(),now(),'DONE',now()
from lt_users u cross join generate_series(1,4) j;
insert into saved_places (processing_job_id,user_id,place_name,region,category,latitude,longitude,source_url,source_platform,created_at,video_notes)
select pj.id, pj.user_id, 'loadtest-place-'||p, 'Seoul','cafe',37.5+p*0.001,127.0+pj.id*0.00001, pj.source_url, 'YOUTUBE', now(),
       repeat('Synthetic travel note. ',8)
from processing_jobs pj join lt_users u on u.id = pj.user_id cross join generate_series(1,10) p;
insert into bookmark_folders (user_id,name,color,created_at)
select u.id, 'loadtest-folder-'||f, '#ff0000', now() from lt_users u cross join generate_series(1,2) f;
create temp table lt_places on commit drop as select id, row_number() over (order by id) - 1 as k from places;
insert into bookmarks (user_id,place_id,folder_id,created_at)
select u.id, lp.id,
       case when b % 3 = 0 then null else
         (select bf.id from bookmark_folders bf where bf.user_id = u.id and bf.name = 'loadtest-folder-'||(1 + b % 2)) end,
       now()
from lt_users u cross join generate_series(1,10) b
join lt_places lp on lp.k = ((u.n-1)*10+b) % (select count(*) from lt_places);
insert into trips (user_id,title,start_date,end_date,created_at,updated_at)
select u.id, 'loadtest-trip-'||t, current_date+30, current_date+32, now(), now() from lt_users u cross join generate_series(1,2) t;
insert into itineraries (trip_id,day_number,date)
select t.id, d, t.start_date + (d-1) from trips t join lt_users u on u.id = t.user_id cross join generate_series(1,3) d;
insert into trip_places (itinerary_id,place_name,region,category,latitude,longitude,visit_order,source,created_at)
select i.id, 'loadtest-stop-'||s, 'Jeju','cafe', 33.4+s*0.001, 126.5, s-1, 'NORMAL', now()
from itineraries i join trips t on t.id = i.trip_id join lt_users u on u.id = t.user_id cross join generate_series(1,4) s;
insert into notifications (user_id,itinerary_id,trip_place_id,title,body,precipitation_prob,is_read,created_at)
select u.id, i.id, tp.id, 'loadtest-rain', 'umbrella', 0.7, false, now()
from lt_users u
join trips t on t.user_id = u.id and t.title = 'loadtest-trip-1'
join itineraries i on i.trip_id = t.id and i.day_number = 1
join trip_places tp on tp.itinerary_id = i.id and tp.visit_order < 3;
select (select count(*) from users where {LT}) users,
       (select count(*) from saved_places sp join users u on u.id = sp.user_id where u.{LT}) saved_places,
       (select count(*) from bookmarks b join users u on u.id = b.user_id where u.{LT}) bookmarks,
       (select count(*) from trips t join users u on u.id = t.user_id where u.{LT}) trips,
       (select count(*) from notifications n join users u on u.id = n.user_id where u.{LT}) notifications;
"""

IDS = f"""
select coalesce(json_agg(json_build_object('userId', u.id, 'tripId',
  (select min(t.id) from trips t where t.user_id = u.id)) order by u.id), '[]')
from users u where u.{LT};
"""

CLEANUP = f"""
create temp table lt_users on commit drop as select id from users where {LT};
delete from notifications where user_id in (select id from lt_users);
delete from user_preference_signals where user_id in (select id from lt_users);
delete from user_preferences where user_id in (select id from lt_users);
delete from trip_replan_jobs where user_id in (select id from lt_users);
delete from trip_drafts where user_id in (select id from lt_users);
delete from trip_places where itinerary_id in
  (select i.id from itineraries i join trips t on t.id = i.trip_id where t.user_id in (select id from lt_users));
delete from itineraries where trip_id in (select id from trips where user_id in (select id from lt_users));
delete from trips where user_id in (select id from lt_users);
delete from bookmarks where user_id in (select id from lt_users);
delete from bookmark_folders where user_id in (select id from lt_users);
delete from saved_places where user_id in (select id from lt_users);
delete from processing_jobs where user_id in (select id from lt_users);
delete from users where id in (select id from lt_users);
select count(*) as remaining_loadtest_users from users where {LT};
"""


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ''
    sql = {'seed': SEED, 'ids': IDS, 'cleanup': CLEANUP}.get(mode)
    env_file = os.environ.get('PROD_DB_ENV_FILE')
    if not sql or not env_file:
        print(__doc__, file=sys.stderr)
        return 2
    env = {**os.environ, **pg_env(env_file)}
    env.pop('PGOPTIONS', None)
    args = [find_psql(), '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-1']
    args += ['-A', '-t'] if mode == 'ids' else ['-A', '-F', ' | ']
    body = "set statement_timeout = '60s';\n" + sql
    return subprocess.run(args, input=body, text=True, env=env).returncode


if __name__ == '__main__':
    sys.exit(main())
