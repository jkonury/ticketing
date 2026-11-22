-- 입장 토큰 Hash를 TTL과 함께 원자적으로 기록한다.
-- queue:admission:<token>에는 해시 태그가 없어 회차 키와 같은 스크립트에 넣지 않고 이 키 하나만 다룬다.
-- KEYS[1]=queue:admission:<token>
-- ARGV[1]=memberId, ARGV[2]=scheduleId, ARGV[3]=TTL(ms)
redis.call('HSET', KEYS[1], 'memberId', ARGV[1], 'scheduleId', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
