-- KEYS[1]=스냅샷 Hash, KEYS[2]=Stream, KEYS[3..n+2]=좌석별 선점 키, KEYS[n+3]=요청 지문 키
-- ARGV[1]=owner, ARGV[2]=ttl(ms), ARGV[3]=now(epoch ms), ARGV[4]=fingerprint, ARGV[5..]=seatId
-- 선점 가능 여부는 스냅샷이 아니라 선점 키와 SOLD로 판단한다(TTL 만료는 스냅샷에 반영되지 않는다).
local n = #ARGV - 4
local unavailable = {}
local owners = {}
local same = ARGV[4] ~= ''
for i = 1, n do
  local seat = ARGV[4 + i]
  local owner = redis.call('GET', KEYS[2 + i])
  local sold = redis.call('HGET', KEYS[1], seat) == 'SOLD'
  if sold or owner then
    unavailable[#unavailable + 1] = seat
    owners[#owners + 1] = owner or false
    if sold or not owner then same = false end
  end
end
if #unavailable > 0 then
  if same then
    local requestOwner = redis.call('GET', KEYS[3 + n])
    if not requestOwner then same = false end
    for _, owner in ipairs(owners) do
      if owner ~= requestOwner then same = false end
    end
  end
  table.insert(unavailable, 1, same and 'same' or 'busy')
  return unavailable
end
local exp = tonumber(ARGV[3]) + tonumber(ARGV[2])
local held = 'HELD:' .. exp
for i = 1, n do
  redis.call('SET', KEYS[2 + i], ARGV[1], 'PX', ARGV[2])
  redis.call('HSET', KEYS[1], ARGV[4 + i], held)
end
if ARGV[4] ~= '' then redis.call('SET', KEYS[3 + n], ARGV[1], 'PX', ARGV[2]) end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
  'seatIds', table.concat(ARGV, ',', 5), 'status', 'HELD', 'holdExpiresAt', tostring(exp), 'at', ARGV[3])
-- 예열 회차의 스냅샷·Stream도 생성과 같은 Lua 안에서 만료시켜 비정상 종료에 대비한다.
if string.sub(KEYS[1], 1, 15) == 'booking:warmup:' then
  redis.call('PEXPIRE', KEYS[1], 30000)
  redis.call('PEXPIRE', KEYS[2], 30000)
end
return {}
