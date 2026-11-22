-- KEYS: waiting, seen / ARGV: memberId
-- 이탈 중간에 같은 회원의 재등록이 끼어들어 새 seen 기록이 지워지지 않도록 두 제거를 한 번에 처리한다. waiting에서 제거된 수를 반환한다.
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return removed
