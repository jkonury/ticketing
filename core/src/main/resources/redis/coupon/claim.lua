-- KEYS[1]=남은 수량, KEYS[2]=받은 회원 Set (같은 {definitionId} 해시 태그)
-- ARGV[1]=memberId
local stock = redis.call('GET', KEYS[1])
if not stock then
  return 'NOT_INITIALIZED'
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return 'DUPLICATE'
end
if tonumber(stock) <= 0 then
  return 'SOLD_OUT'
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 'OK'
