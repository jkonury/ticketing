-- KEYS[1]=스냅샷, KEYS[2]=Stream, KEYS[3..]=선점 키. ARGV[1]=owner, ARGV[2]=now, ARGV[3..]=seatId
for i = 1, #ARGV - 2 do
  redis.call('HSET', KEYS[1], ARGV[2 + i], 'SOLD')
  if redis.call('GET', KEYS[2 + i]) == ARGV[1] then
    redis.call('DEL', KEYS[2 + i])
  end
end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
  'seatIds', table.concat(ARGV, ',', 3), 'status', 'SOLD', 'at', ARGV[2])
return 1
