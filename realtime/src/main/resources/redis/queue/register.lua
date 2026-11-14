-- KEYS: waiting, seen, sequence, admitted 조회 키 (모두 같은 회차 해시 태그)
-- ARGV: memberId, nowMillis
-- 입장 처리 스크립트와 같은 키 집합을 한 번에 보므로 "이미 입장했는가 → 대기 중인가 → 신규 등록"의 판정이 입장 전이와 겹치지 않는다.
local token = redis.call('GET', KEYS[4])
if token then
    return {2, 0, token}
end
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
    return {0, rank}
end
local sequence = redis.call('INCR', KEYS[3])
redis.call('ZADD', KEYS[1], sequence, ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
return {1, redis.call('ZRANK', KEYS[1], ARGV[1])}
