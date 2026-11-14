-- KEYS[1]=스냅샷, KEYS[2]=Stream, KEYS[3..]=선점 키. ARGV[1]=owner, ARGV[2]=now, ARGV[3..]=seatId
-- 소유자가 같은 선점 키만 해제한다. 이미 SOLD인 좌석은 선점 키만 지우고 스냅샷을 AVAILABLE로 되돌리지 않는다
-- (선점 키가 유실돼 같은 좌석을 두 예매가 선점했을 때, 확정에 진 쪽의 해제가 이긴 쪽의 SOLD를 덮어쓰지 않게 한다).
local released = {}
for i = 1, #ARGV - 2 do
  if redis.call('GET', KEYS[2 + i]) == ARGV[1] then
    redis.call('DEL', KEYS[2 + i])
    if redis.call('HGET', KEYS[1], ARGV[2 + i]) ~= 'SOLD' then
      redis.call('HSET', KEYS[1], ARGV[2 + i], 'AVAILABLE')
      released[#released + 1] = ARGV[2 + i]
    end
  end
end
if #released > 0 then
  redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
    'seatIds', table.concat(released, ','), 'status', 'AVAILABLE', 'at', ARGV[2])
end
return released
