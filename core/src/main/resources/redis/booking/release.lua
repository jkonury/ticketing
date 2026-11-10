-- KEYS[1]=스냅샷, KEYS[2]=Stream, KEYS[3..]=선점 키. ARGV[1]=owner, ARGV[2]=now, ARGV[3..]=seatId
-- 소유자가 같은 선점 키만 해제한다.
local released = {}
for i = 1, #ARGV - 2 do
  if redis.call('GET', KEYS[2 + i]) == ARGV[1] then
    redis.call('DEL', KEYS[2 + i])
    redis.call('HSET', KEYS[1], ARGV[2 + i], 'AVAILABLE')
    released[#released + 1] = ARGV[2 + i]
  end
end
if #released > 0 then
  redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
    'seatIds', table.concat(released, ','), 'status', 'AVAILABLE', 'at', ARGV[2])
end
return released
