-- KEYS: waiting, seen / ARGV: memberId, nowMillis
-- 대기 중인 회원만 생존 신호를 갱신한다. 순번 조회 뒤 입장 처리가 끼어들어도 입장한 회원의 seen 항목이 되살아나지 않는다.
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
    return 1
end
return 0
