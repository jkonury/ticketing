-- KEYS[1]=스냅샷 Hash, KEYS[2]=Stream, KEYS[3..]=좌석별 선점 키 (ARGV[4..]와 같은 순서)
-- ARGV[1]=owner, ARGV[2]=ttl(ms), ARGV[3]=now(epoch ms), ARGV[4..]=seatId
-- 선점 가능 여부는 스냅샷이 아니라 선점 키와 SOLD로 판단한다(TTL 만료는 스냅샷에 반영되지 않는다).
local n = #ARGV - 3
local unavailable = {}
for i = 1, n do
  local seat = ARGV[3 + i]
  if redis.call('HGET', KEYS[1], seat) == 'SOLD' or redis.call('EXISTS', KEYS[2 + i]) == 1 then
    unavailable[#unavailable + 1] = seat
  end
end
if #unavailable > 0 then
  return unavailable
end
local exp = tonumber(ARGV[3]) + tonumber(ARGV[2])
local held = 'HELD:' .. exp
for i = 1, n do
  redis.call('SET', KEYS[2 + i], ARGV[1], 'PX', ARGV[2])
  redis.call('HSET', KEYS[1], ARGV[3 + i], held)
end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
  'seatIds', table.concat(ARGV, ',', 4), 'status', 'HELD', 'holdExpiresAt', tostring(exp), 'at', ARGV[3])
return {}
