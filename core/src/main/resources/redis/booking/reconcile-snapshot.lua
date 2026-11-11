-- KEYS: snapshot, stream, hold keys; ARGV: now, seat count, seat IDs, sold IDs
local count = tonumber(ARGV[2])
local sold = {}
for i = 3 + count, #ARGV do sold[ARGV[i]] = true end
local groups = {}
local changed = 0
for i = 1, count do
  local seat = ARGV[2 + i]
  local status = 'AVAILABLE'
  if sold[seat] then
    status = 'SOLD'
  else
    local ttl = redis.call('PTTL', KEYS[2 + i])
    if ttl >= 0 then status = 'HELD:' .. tostring(tonumber(ARGV[1]) + ttl) end
  end
  if redis.call('HGET', KEYS[1], seat) ~= status then
    redis.call('HSET', KEYS[1], seat, status)
    if not groups[status] then groups[status] = {} end
    table.insert(groups[status], seat)
    changed = changed + 1
  end
end
for status, seats in pairs(groups) do
  local expires = string.match(status, '^HELD:(.+)$')
  if expires then
    redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
      'seatIds', table.concat(seats, ','), 'status', 'HELD', 'holdExpiresAt', expires, 'at', ARGV[1])
  else
    redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
      'seatIds', table.concat(seats, ','), 'status', status, 'at', ARGV[1])
  end
end
return changed
