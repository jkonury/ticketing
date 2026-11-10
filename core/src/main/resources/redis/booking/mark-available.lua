-- KEYS[1]=스냅샷, KEYS[2]=Stream. ARGV[1]=now, ARGV[2..]=seatId
for i = 2, #ARGV do
  redis.call('HSET', KEYS[1], ARGV[i], 'AVAILABLE')
end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*',
  'seatIds', table.concat(ARGV, ',', 2), 'status', 'AVAILABLE', 'at', ARGV[1])
return 1
